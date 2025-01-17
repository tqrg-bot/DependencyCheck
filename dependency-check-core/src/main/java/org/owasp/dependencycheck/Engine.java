/*
 * This file is part of dependency-check-core.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Copyright (c) 2012 Jeremy Long. All Rights Reserved.
 */
package org.owasp.dependencycheck;

import org.owasp.dependencycheck.analyzer.AnalysisPhase;
import org.owasp.dependencycheck.analyzer.Analyzer;
import org.owasp.dependencycheck.analyzer.AnalyzerService;
import org.owasp.dependencycheck.analyzer.FileTypeAnalyzer;
import org.owasp.dependencycheck.data.nvdcve.ConnectionFactory;
import org.owasp.dependencycheck.data.nvdcve.CveDB;
import org.owasp.dependencycheck.data.nvdcve.DatabaseException;
import org.owasp.dependencycheck.data.update.CachedWebDataSource;
import org.owasp.dependencycheck.data.update.UpdateService;
import org.owasp.dependencycheck.data.update.exception.UpdateException;
import org.owasp.dependencycheck.dependency.Dependency;
import org.owasp.dependencycheck.exception.ExceptionCollection;
import org.owasp.dependencycheck.exception.InitializationException;
import org.owasp.dependencycheck.exception.NoDataException;
import org.owasp.dependencycheck.utils.InvalidSettingException;
import org.owasp.dependencycheck.utils.Settings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileFilter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Scans files, directories, etc. for Dependencies. Analyzers are loaded and
 * used to process the files found by the scan, if a file is encountered and an
 * Analyzer is associated with the file type then the file is turned into a
 * dependency.
 *
 * @author Jeremy Long
 */
public class Engine implements FileFilter {

    /**
     * The list of dependencies.
     */
    private final List<Dependency> dependencies = Collections.synchronizedList(new ArrayList<Dependency>());
    /**
     * A Map of analyzers grouped by Analysis phase.
     */
    private final Map<AnalysisPhase, List<Analyzer>> analyzers = new EnumMap<AnalysisPhase, List<Analyzer>>(AnalysisPhase.class);

    /**
     * A Map of analyzers grouped by Analysis phase.
     */
    private final Set<FileTypeAnalyzer> fileTypeAnalyzers = new HashSet<FileTypeAnalyzer>();

    /**
     * The ClassLoader to use when dynamically loading Analyzer and Update
     * services.
     */
    private ClassLoader serviceClassLoader = Thread.currentThread().getContextClassLoader();
    /**
     * The Logger for use throughout the class.
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(Engine.class);

    /**
     * Creates a new Engine.
     *
     * @throws DatabaseException thrown if there is an error connecting to the
     * database
     */
    public Engine() throws DatabaseException {
        initializeEngine();
    }

    /**
     * Creates a new Engine.
     *
     * @param serviceClassLoader a reference the class loader being used
     * @throws DatabaseException thrown if there is an error connecting to the
     * database
     */
    public Engine(ClassLoader serviceClassLoader) throws DatabaseException {
        this.serviceClassLoader = serviceClassLoader;
        initializeEngine();
    }

    /**
     * Creates a new Engine using the specified classloader to dynamically load
     * Analyzer and Update services.
     *
     * @throws DatabaseException thrown if there is an error connecting to the
     * database
     */
    protected final void initializeEngine() throws DatabaseException {
        ConnectionFactory.initialize();
        loadAnalyzers();
    }

    /**
     * Properly cleans up resources allocated during analysis.
     */
    public void cleanup() {
        ConnectionFactory.cleanup();
    }

    /**
     * Loads the analyzers specified in the configuration file (or system
     * properties).
     */
    private void loadAnalyzers() {
        if (!analyzers.isEmpty()) {
            return;
        }
        for (AnalysisPhase phase : AnalysisPhase.values()) {
            analyzers.put(phase, new ArrayList<Analyzer>());
        }

        final AnalyzerService service = new AnalyzerService(serviceClassLoader);
        final List<Analyzer> iterator = service.getAnalyzers();
        for (Analyzer a : iterator) {
            analyzers.get(a.getAnalysisPhase()).add(a);
            if (a instanceof FileTypeAnalyzer) {
                this.fileTypeAnalyzers.add((FileTypeAnalyzer) a);
            }
        }
    }

    /**
     * Get the List of the analyzers for a specific phase of analysis.
     *
     * @param phase the phase to get the configured analyzers.
     * @return the analyzers loaded
     */
    public List<Analyzer> getAnalyzers(AnalysisPhase phase) {
        return analyzers.get(phase);
    }

    /**
     * Get the dependencies identified.
     * The returned list is a reference to the engine's synchronized list. You must synchronize on it, when you modify
     * and iterate over it from multiple threads. E.g. this holds for analyzers supporting parallel processing during
     * their analysis phase.
     *
     * @return the dependencies identified
     * @see Collections#synchronizedList(List)
     * @see Analyzer#supportsParallelProcessing()
     */
    public List<Dependency> getDependencies() {
        return dependencies;
    }

    /**
     * Sets the dependencies.
     *
     * @param dependencies the dependencies
     */
    public void setDependencies(List<Dependency> dependencies) {
        synchronized (this.dependencies) {
            this.dependencies.clear();
            this.dependencies.addAll(dependencies);
        }
    }

    /**
     * Scans an array of files or directories. If a directory is specified, it
     * will be scanned recursively. Any dependencies identified are added to the
     * dependency collection.
     *
     * @param paths an array of paths to files or directories to be analyzed
     * @return the list of dependencies scanned
     * @since v0.3.2.5
     */
    public List<Dependency> scan(String[] paths) {
        final List<Dependency> deps = new ArrayList<Dependency>();
        for (String path : paths) {
            final List<Dependency> d = scan(path);
            if (d != null) {
                deps.addAll(d);
            }
        }
        return deps;
    }

    /**
     * Scans a given file or directory. If a directory is specified, it will be
     * scanned recursively. Any dependencies identified are added to the
     * dependency collection.
     *
     * @param path the path to a file or directory to be analyzed
     * @return the list of dependencies scanned
     */
    public List<Dependency> scan(String path) {
        final File file = new File(path);
        return scan(file);
    }

    /**
     * Scans an array of files or directories. If a directory is specified, it
     * will be scanned recursively. Any dependencies identified are added to the
     * dependency collection.
     *
     * @param files an array of paths to files or directories to be analyzed.
     * @return the list of dependencies
     * @since v0.3.2.5
     */
    public List<Dependency> scan(File[] files) {
        final List<Dependency> deps = new ArrayList<Dependency>();
        for (File file : files) {
            final List<Dependency> d = scan(file);
            if (d != null) {
                deps.addAll(d);
            }
        }
        return deps;
    }

    /**
     * Scans a collection of files or directories. If a directory is specified,
     * it will be scanned recursively. Any dependencies identified are added to
     * the dependency collection.
     *
     * @param files a set of paths to files or directories to be analyzed
     * @return the list of dependencies scanned
     * @since v0.3.2.5
     */
    public List<Dependency> scan(Collection<File> files) {
        final List<Dependency> deps = new ArrayList<Dependency>();
        for (File file : files) {
            final List<Dependency> d = scan(file);
            if (d != null) {
                deps.addAll(d);
            }
        }
        return deps;
    }

    /**
     * Scans a given file or directory. If a directory is specified, it will be
     * scanned recursively. Any dependencies identified are added to the
     * dependency collection.
     *
     * @param file the path to a file or directory to be analyzed
     * @return the list of dependencies scanned
     * @since v0.3.2.4
     */
    public List<Dependency> scan(File file) {
        if (file.exists()) {
            if (file.isDirectory()) {
                return scanDirectory(file);
            } else {
                final Dependency d = scanFile(file);
                if (d != null) {
                    final List<Dependency> deps = new ArrayList<Dependency>();
                    deps.add(d);
                    return deps;
                }
            }
        }
        return null;
    }

    /**
     * Recursively scans files and directories. Any dependencies identified are
     * added to the dependency collection.
     *
     * @param dir the directory to scan
     * @return the list of Dependency objects scanned
     */
    protected List<Dependency> scanDirectory(File dir) {
        final File[] files = dir.listFiles();
        final List<Dependency> deps = new ArrayList<Dependency>();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    final List<Dependency> d = scanDirectory(f);
                    if (d != null) {
                        deps.addAll(d);
                    }
                } else {
                    final Dependency d = scanFile(f);
                    deps.add(d);
                }
            }
        }
        return deps;
    }

    /**
     * Scans a specified file. If a dependency is identified it is added to the
     * dependency collection.
     *
     * @param file The file to scan
     * @return the scanned dependency
     */
    protected Dependency scanFile(File file) {
        Dependency dependency = null;
        if (file.isFile()) {
            if (accept(file)) {
                dependency = new Dependency(file);
                String sha1 = dependency.getSha1sum();
                boolean found = false;
                synchronized (dependencies) {
                    if (sha1 != null) {
                        for (Dependency existing : dependencies) {
                            if (sha1.equals(existing.getSha1sum())) {
                                found = true;
                                dependency = existing;
                            }
                        }
                    }
                    if (!found) {
                        dependencies.add(dependency);
                    }
                }
            } else {
                LOGGER.debug("Path passed to scanFile(File) is not a file: {}. Skipping the file.", file);
            }
        }
        return dependency;
    }

    /**
     * Runs the analyzers against all of the dependencies. Since the mutable
     * dependencies list is exposed via {@link #getDependencies()}, this method
     * iterates over a copy of the dependencies list. Thus, the potential for
     * {@link java.util.ConcurrentModificationException}s is avoided, and
     * analyzers may safely add or remove entries from the dependencies list.
     *
     * Every effort is made to complete analysis on the dependencies. In some
     * cases an exception will occur with part of the analysis being performed
     * which may not affect the entire analysis. If an exception occurs it will
     * be included in the thrown exception collection.
     *
     * @throws ExceptionCollection a collections of any exceptions that occurred
     * during analysis
     */
    public void analyzeDependencies() throws ExceptionCollection {
        final List<Throwable> exceptions = Collections.synchronizedList(new ArrayList<Throwable>());
        boolean autoUpdate = true;
        try {
            autoUpdate = Settings.getBoolean(Settings.KEYS.AUTO_UPDATE);
        } catch (InvalidSettingException ex) {
            LOGGER.debug("Invalid setting for auto-update; using true.");
            exceptions.add(ex);
        }
        if (autoUpdate) {
            try {
                doUpdates();
            } catch (UpdateException ex) {
                exceptions.add(ex);
                LOGGER.warn("Unable to update Cached Web DataSource, using local "
                        + "data instead. Results may not include recent vulnerabilities.");
                LOGGER.debug("Update Error", ex);
            }
        }

        //need to ensure that data exists
        try {
            ensureDataExists();
        } catch (NoDataException ex) {
            throwFatalExceptionCollection("Unable to continue dependency-check analysis.", ex, exceptions);
        } catch (DatabaseException ex) {
            throwFatalExceptionCollection("Unable to connect to the dependency-check database.", ex, exceptions);
        }

        LOGGER.debug("\n----------------------------------------------------\nBEGIN ANALYSIS\n----------------------------------------------------");
        LOGGER.info("Analysis Started");
        final long analysisStart = System.currentTimeMillis();

        // analysis phases
        for (AnalysisPhase phase : AnalysisPhase.values()) {
            final List<Analyzer> analyzerList = analyzers.get(phase);

            for (final Analyzer a : analyzerList) {
                final long analyzerStart = System.currentTimeMillis();
                try {
                    initializeAnalyzer(a);
                } catch (InitializationException ex) {
                    exceptions.add(ex);
                    continue;
                }

                executeAnalysisTasks(exceptions, a);

                final long analyzerDurationMillis = System.currentTimeMillis() - analyzerStart;
                final long analyzerDurationSeconds = TimeUnit.MILLISECONDS.toSeconds(analyzerDurationMillis);
                LOGGER.info("Finished {}. Took {} secs.", a.getName(), analyzerDurationSeconds);
            }
        }
        for (AnalysisPhase phase : AnalysisPhase.values()) {
            final List<Analyzer> analyzerList = analyzers.get(phase);

            for (Analyzer a : analyzerList) {
                closeAnalyzer(a);
            }
        }

        LOGGER.debug("\n----------------------------------------------------\nEND ANALYSIS\n----------------------------------------------------");
        LOGGER.info("Analysis Complete ({} ms)", System.currentTimeMillis() - analysisStart);
        if (exceptions.size() > 0) {
            throw new ExceptionCollection("One or more exceptions occurred during dependency-check analysis", exceptions);
        }
    }

    private void executeAnalysisTasks(List<Throwable> exceptions, Analyzer analyzer) throws ExceptionCollection {
        LOGGER.debug("Starting {}", analyzer.getName());
        final List<AnalysisTask> analysisTasks = getAnalysisTasks(analyzer, exceptions);
        final ExecutorService executorService = getExecutorService(analyzer);

        try {
            List<Future<Void>> results = executorService.invokeAll(analysisTasks, 10, TimeUnit.MINUTES);

            // ensure there was no exception during execution
            for (Future<Void> result : results) {
                try {
                    result.get();
                } catch (ExecutionException e) {
                    throwFatalExceptionCollection("Analysis task failed with a fatal exception.", e, exceptions);
                }
            }
        } catch (InterruptedException e) {
            throwFatalExceptionCollection("Analysis has been interrupted.", e, exceptions);
        }
    }

    private List<AnalysisTask> getAnalysisTasks(Analyzer analyzer, List<Throwable> exceptions) {
        final List<AnalysisTask> result = new ArrayList<AnalysisTask>();
        synchronized (dependencies) {
            for (final Dependency dependency : dependencies) {
                AnalysisTask task = new AnalysisTask(analyzer, dependency, this, exceptions);
                result.add(task);
            }
        }
        return result;
    }

    private ExecutorService getExecutorService(Analyzer analyzer) {
        if (analyzer.supportsParallelProcessing()) {
            // just a fair trade-off that should be reasonable for all analyzer types
            int maximumNumberOfThreads = 4 * Runtime.getRuntime().availableProcessors();

            LOGGER.debug("Parallel processing with up to {} threads: {}.", maximumNumberOfThreads, analyzer.getName());
            return Executors.newFixedThreadPool(maximumNumberOfThreads);
        } else {
            LOGGER.debug("Parallel processing is not supported: {}.", analyzer.getName());
            return Executors.newSingleThreadExecutor();
        }
    }

    /**
     * Initializes the given analyzer.
     *
     * @param analyzer the analyzer to initialize
     * @return the initialized analyzer
     * @throws InitializationException thrown when there is a problem
     * initializing the analyzer
     */
    protected Analyzer initializeAnalyzer(Analyzer analyzer) throws InitializationException {
        try {
            LOGGER.debug("Initializing {}", analyzer.getName());
            analyzer.initialize();
        } catch (InitializationException ex) {
            LOGGER.error("Exception occurred initializing {}.", analyzer.getName());
            LOGGER.debug("", ex);
            try {
                analyzer.close();
            } catch (Throwable ex1) {
                LOGGER.trace("", ex1);
            }
            throw ex;
        } catch (Throwable ex) {
            LOGGER.error("Unexpected exception occurred initializing {}.", analyzer.getName());
            LOGGER.debug("", ex);
            try {
                analyzer.close();
            } catch (Throwable ex1) {
                LOGGER.trace("", ex1);
            }
            throw new InitializationException("Unexpected Exception", ex);
        }
        return analyzer;
    }

    /**
     * Closes the given analyzer.
     *
     * @param analyzer the analyzer to close
     */
    protected void closeAnalyzer(Analyzer analyzer) {
        LOGGER.debug("Closing Analyzer '{}'", analyzer.getName());
        try {
            analyzer.close();
        } catch (Throwable ex) {
            LOGGER.trace("", ex);
        }
    }

    /**
     * Cycles through the cached web data sources and calls update on all of
     * them.
     *
     * @throws UpdateException thrown if the operation fails
     */
    public void doUpdates() throws UpdateException {
        LOGGER.info("Checking for updates");
        final long updateStart = System.currentTimeMillis();
        final UpdateService service = new UpdateService(serviceClassLoader);
        final Iterator<CachedWebDataSource> iterator = service.getDataSources();
        while (iterator.hasNext()) {
            final CachedWebDataSource source = iterator.next();
            source.update();
        }
        LOGGER.info("Check for updates complete ({} ms)", System.currentTimeMillis() - updateStart);
    }

    /**
     * Returns a full list of all of the analyzers. This is useful for reporting
     * which analyzers where used.
     *
     * @return a list of Analyzers
     */
    public List<Analyzer> getAnalyzers() {
        final List<Analyzer> ret = new ArrayList<Analyzer>();
        for (AnalysisPhase phase : AnalysisPhase.values()) {
            final List<Analyzer> analyzerList = analyzers.get(phase);
            ret.addAll(analyzerList);
        }
        return ret;
    }

    /**
     * Checks all analyzers to see if an extension is supported.
     *
     * @param file a file extension
     * @return true or false depending on whether or not the file extension is
     * supported
     */
    @Override
    public boolean accept(File file) {
        if (file == null) {
            return false;
        }
        boolean scan = false;
        for (FileTypeAnalyzer a : this.fileTypeAnalyzers) {
            /* note, we can't break early on this loop as the analyzers need to know if
             they have files to work on prior to initialization */
            scan |= a.accept(file);
        }
        return scan;
    }

    /**
     * Returns the set of file type analyzers.
     *
     * @return the set of file type analyzers
     */
    public Set<FileTypeAnalyzer> getFileTypeAnalyzers() {
        return this.fileTypeAnalyzers;
    }

    /**
     * Adds a file type analyzer. This has been added solely to assist in unit
     * testing the Engine.
     *
     * @param fta the file type analyzer to add
     */
    protected void addFileTypeAnalyzer(FileTypeAnalyzer fta) {
        this.fileTypeAnalyzers.add(fta);
    }

    /**
     * Checks the CPE Index to ensure documents exists. If none exist a
     * NoDataException is thrown.
     *
     * @throws NoDataException thrown if no data exists in the CPE Index
     * @throws DatabaseException thrown if there is an exception opening the
     * database
     */
    private void ensureDataExists() throws NoDataException, DatabaseException {
        final CveDB cve = new CveDB();
        try {
            cve.open();
            if (!cve.dataExists()) {
                throw new NoDataException("No documents exist");
            }
        } catch (DatabaseException ex) {
            throw new NoDataException(ex.getMessage(), ex);
        } finally {
            cve.close();
        }
    }

    private void throwFatalExceptionCollection(String message, Throwable throwable, List<Throwable> exceptions) throws ExceptionCollection {
        LOGGER.error("{}\n\n{}", throwable.getMessage(), message);
        LOGGER.debug("", throwable);
        exceptions.add(throwable);
        throw new ExceptionCollection(message, exceptions, true);
    }
}
