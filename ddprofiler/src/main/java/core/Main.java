/**
 * @author Raul - raulcf@csail.mit.edu
 *
 */
package core;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Properties;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.LocatedFileStatus;
import org.apache.hadoop.fs.RemoteIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import comm.WebServer;
import core.config.CommandLineArgs;
import core.config.ConfigKey;
import core.config.ProfilerConfig;
import inputoutput.conn.DBType;
import inputoutput.conn.DBUtils;
import joptsimple.OptionParser;
import metrics.Metrics;
import store.Store;
import store.StoreFactory;

public class Main {

    final private Logger LOG = LoggerFactory.getLogger(Main.class.getName());

    public enum ExecutionMode {
	ONLINE(0), OFFLINE_FILES(1), OFFLINE_DB(2), BENCHMARK(3);

	int mode;

	ExecutionMode(int mode) {
	    this.mode = mode;
	}
    }

    public void startProfiler(ProfilerConfig pc) {

	long start = System.nanoTime();

	// Default is elastic, if we have more in the future, just pass a
	// property
	// to configure this
	Store s = StoreFactory.makeStoreOfType(pc.getInt(ProfilerConfig.STORE_TYPE), pc);

	// for test purpose, use this and comment above line when elasticsearch
	// is
	// not configured
	// Store s = StoreFactory.makeNullStore(pc);

	Conductor c = new Conductor(pc, s);
	c.start();

	String dbName = pc.getString(ProfilerConfig.DB_NAME);
	int executionMode = pc.getInt(ProfilerConfig.EXECUTION_MODE);
	if (executionMode == ExecutionMode.ONLINE.mode) {
	    // Start infrastructure for REST server
	    WebServer ws = new WebServer(pc, c);
	    ws.init();
	} else if (executionMode == ExecutionMode.OFFLINE_FILES.mode) {
	    // Run with the configured input parameters and produce results to
	    // file
	    // (?)
	    String pathToSources = pc.getString(ProfilerConfig.SOURCES_TO_ANALYZE_FOLDER);
	    this.readDirectoryAndCreateTasks(dbName, c, pathToSources, pc.getString(ProfilerConfig.CSV_SEPARATOR));
	} else if (executionMode == ExecutionMode.OFFLINE_DB.mode) {
	    this.readTablesFromDBAndCreateTasks(dbName, c);
	} else if (executionMode == ExecutionMode.BENCHMARK.mode) {
	    // Piggyback property to benchmark system with one file
	    String pathToSource = pc.getString(ProfilerConfig.SOURCES_TO_ANALYZE_FOLDER);
	    this.benchmarkSystem(c, pathToSource, pc.getString(ProfilerConfig.CSV_SEPARATOR));
	}

	while (c.isTherePendingWork()) {
	    try {
		Thread.sleep(3000);
	    } catch (InterruptedException e) {
		// TODO Auto-generated catch block
		e.printStackTrace();
	    }
	}

	c.stop();
	s.tearDownStore();

	long end = System.nanoTime();
	LOG.info("Finished processing in {}", (end - start));
    }

    public static void main(String args[]) {

	// Get Properties with command line configuration
	List<ConfigKey> configKeys = ProfilerConfig.getAllConfigKey();
	OptionParser parser = new OptionParser();
	// Unrecognized options are passed through to the query
	parser.allowsUnrecognizedOptions();
	CommandLineArgs cla = new CommandLineArgs(args, parser, configKeys);
	Properties commandLineProperties = cla.getProperties();

	// Check if the user requests help
	for (String a : args) {
	    if (a.contains("help") || a.equals("?")) {
		try {
		    parser.printHelpOn(System.out);
		    System.exit(0);
		} catch (IOException e) {
		    e.printStackTrace();
		}
	    }
	}

	// TODO: get properties from file ?

	// TODO: Merge all properties into one single Properties object to be
	// validated
	// Pay attention to redefinition of properties and define a priority to
	// fix
	// conflicts.

	Properties validatedProperties = validateProperties(commandLineProperties);

	ProfilerConfig pc = new ProfilerConfig(validatedProperties);

	// Start main

	configureMetricsReporting(pc);

	// config logs
	configLog();

	Main m = new Main();
	m.startProfiler(pc);

    }

    private static void configLog() {
	final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger("com.zaxxer.hikari");
	if (!(logger instanceof ch.qos.logback.classic.Logger)) {
	    return;
	}
	ch.qos.logback.classic.Logger logbackLogger = (ch.qos.logback.classic.Logger) logger;
	logbackLogger.setLevel(ch.qos.logback.classic.Level.WARN);
    }

    static private void configureMetricsReporting(ProfilerConfig pc) {
	int reportConsole = pc.getInt(ProfilerConfig.REPORT_METRICS_CONSOLE);
	if (reportConsole > 0) {
	    Metrics.startConsoleReporter(reportConsole);
	}
    }

    private void readDirectoryAndCreateTasks(String dbName, Conductor c, String pathToSources, String separator) {
	File folder = new File(pathToSources);
	// Get fs scheme to determine how to read files
	Path p = folder.toPath();
	String scheme = p.getFileSystem().provider().getScheme();
	int totalFiles = 0;
	int tt = 0;
	if (scheme.equals("fs")) {
	    File[] filePaths = folder.listFiles();
	    for (File f : filePaths) {
		tt++;
		if (f.isFile()) {
		    String path = f.getParent() + File.separator;
		    String name = f.getName();
		    TaskPackage tp = TaskPackage.makeCSVFileTaskPackage(dbName, path, name, separator);
		    totalFiles++;
		    c.submitTask(tp);
		}
	    }
	} else if (scheme.equals("hdfs")) {
	    Configuration conf = new Configuration();

	    try {
		FileSystem fs = FileSystem.get(conf);
		org.apache.hadoop.fs.Path hdfsPath = new org.apache.hadoop.fs.Path(pathToSources);
		RemoteIterator<LocatedFileStatus> it = fs.listFiles(hdfsPath, false);
		while (it.hasNext()) {
		    LocatedFileStatus lfs = it.next();
		    tt++;
		    if (!lfs.isFile()) {
			continue;
		    }
		    org.apache.hadoop.fs.Path toFile = lfs.getPath();
		    String name = toFile.getName();
		    TaskPackage tp = TaskPackage.makeHDFSCSVFileTaskPackage(dbName, toFile, name, separator);
		    totalFiles++;
		    c.submitTask(tp);
		}
	    } catch (IOException e) {
		e.printStackTrace();
	    }
	}
	LOG.info("Total files submitted for processing: {} - {}", totalFiles, tt);
    }

    private void readTablesFromDBAndCreateTasks(String dbName, Conductor c) {
	Properties dbp = DBUtils.loadDBPropertiesFromFile();
	String dbTypeStr = dbp.getProperty("db_system_name");
	DBType dbType = getType(dbTypeStr);

	String ip = dbp.getProperty("conn_ip");
	String port = dbp.getProperty("port");
	String dbname = dbp.getProperty("conn_path");
	String username = dbp.getProperty("user_name");
	String password = dbp.getProperty("password");
	String dbschema = dbp.getProperty("dbschema");

	LOG.info("Conn to DB on: {}:{}/{}", ip, port, dbname);

	Connection dbConn = DBUtils.getDBConnection(dbType, ip, port, dbname, username, password);

	List<String> tables = DBUtils.getTablesFromDatabase(dbConn, dbschema);
	try {
	    dbConn.close();
	} catch (SQLException e) {
	    e.printStackTrace();
	}
	for (String str : tables) {
	    LOG.info("Detected relational table: {}", str);
	    TaskPackage tp = TaskPackage.makeDBTaskPackage(dbName, dbType, ip, port, dbname, str, username, password);
	    c.submitTask(tp);
	}
    }

    private void benchmarkSystem(Conductor c, String path, String separator) {
	TaskPackage tp = TaskPackage.makeBenchmarkTask(path, separator);
	// Make sure there's always work to process
	while (c.approxQueueLenght() < 30000) {
	    c.submitTask(tp);
	}
    }

    private DBType getType(String type) {
	if (type.equals("mysql"))
	    return DBType.MYSQL;
	else if (type.equals("postgresql"))
	    return DBType.POSTGRESQL;
	else if (type.equals("oracle"))
	    return DBType.ORACLE;
	else
	    return null;
    }

    public static Properties validateProperties(Properties p) {
	// TODO: Go over all properties configured here and validate their
	// ranges,
	// values
	// etc. Stop the program and spit useful doc message when something goes
	// wrong.
	// Return the unmodified properties if everything goes well.

	return p;
    }
}
