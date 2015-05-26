/**
 * (c) 2015 StreamSets, Inc. All rights reserved. May not
 * be copied, modified, or distributed in whole or part without
 * written consent of StreamSets, Inc.
 */
package com.streamsets.pipeline.util;

import com.streamsets.pipeline.MiniSDC;
import com.streamsets.pipeline.MiniSDCTestingUtility;
import org.apache.commons.io.FileUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.server.MiniYARNCluster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

public class ClusterUtil {

  private static final Logger LOG = LoggerFactory.getLogger(ClusterUtil.class);
  private static final String SPARK_TEST_HOME = "SPARK_TEST_HOME";
  private static final String SPARK_PROPERTY_FILE = "SPARK_PROPERTY_FILE";

  private static MiniSDCTestingUtility miniSDCTestingUtility;
  private static URI serverURI;
  private static MiniSDC miniSDC;
  private static MiniYARNCluster miniYarnCluster;

  public static void setupCluster(String testName, String pipelineJson) throws Exception {
    System.setProperty("sdc.testing-mode", "true");
    System.setProperty(MiniSDCTestingUtility.PRESERVE_TEST_DIR, "true");
    miniSDCTestingUtility = new MiniSDCTestingUtility();
    File dataTestDir = miniSDCTestingUtility.getDataTestDir();

    //copy spark files under the test data directory into a dir called "spark"
    File sparkHome = ClusterUtil.createSparkHome(dataTestDir);

    //start mini yarn cluster
    miniYarnCluster = miniSDCTestingUtility.startMiniYarnCluster(testName, 1, 1, 1);
    Configuration config = miniYarnCluster.getConfig();

    long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(10);
    while (config.get(YarnConfiguration.RM_ADDRESS).split(":")[1] == "0") {
      if (System.currentTimeMillis() > deadline) {
        throw new IllegalStateException("Timed out waiting for RM to come up.");
      }
      LOG.debug("RM address still not set in configuration, waiting...");
      TimeUnit.MILLISECONDS.sleep(100);
    }
    LOG.debug("RM at " + config.get(YarnConfiguration.RM_ADDRESS));

    Properties sparkHadoopProps = new Properties();
    for (Map.Entry<String, String> entry : config) {
      sparkHadoopProps.setProperty("spark.hadoop." + entry.getKey(), entry.getValue());
    }

    LOG.debug("Creating spark properties file at " + dataTestDir);
    File propertiesFile =  new File(dataTestDir, "spark.properties");
    propertiesFile.createNewFile();
    FileOutputStream sdcOutStream = new FileOutputStream(propertiesFile);
    sparkHadoopProps.store(sdcOutStream, null);
    sdcOutStream.flush();
    sdcOutStream.close();
    // Need to pass this property file to spark-submit for it pick up yarn confs
    System.setProperty(SPARK_PROPERTY_FILE, propertiesFile.getAbsolutePath());

    File sparkBin = new File(sparkHome, "bin");
    for (File file : sparkBin.listFiles()) {
      MiniSDCTestingUtility.setExecutePermission(file.toPath());
    }

    miniSDC = miniSDCTestingUtility.createMiniSDC(MiniSDC.ExecutionMode.CLUSTER);
    miniSDC.startSDC();
    serverURI = miniSDC.getServerURI();
    miniSDC.createPipeline(pipelineJson);
    miniSDC.startPipeline();
    Thread.sleep(60000);
  }

  public static void tearDownCluster(String testName) throws Exception {
    if (miniSDCTestingUtility != null) {
      miniSDCTestingUtility.stopMiniSDC();
      ClusterUtil.killYarnApp(testName);
      miniSDCTestingUtility.stopMiniYarnCluster();
      miniSDCTestingUtility.cleanupTestDir();
      ClusterUtil.cleanUpYarnDirs(testName);
    }
  }

  public static void cleanUpYarnDirs(String testName) throws IOException {
    if (!Boolean.getBoolean(MiniSDCTestingUtility.PRESERVE_TEST_DIR)) {
      MiniSDCTestingUtility.deleteDir(new File(new File(System.getProperty("user.dir"), "target"), testName));
    }
  }

  public static void killYarnApp(String testName) throws Exception {
    // TODO - remove this hack
    // We dont know app id, but yarn creates its data dir under $HOME/target/TESTNAME, so kill the process by
    // grep for the yarn testname
    String killCmd = signalCommand(testName, "SIGKILL");
    LOG.info("Signal kill command to yarn app " + killCmd);
    String[] killCommand = new String[] { "/usr/bin/env", "bash", "-c", killCmd };
    Process p = Runtime.getRuntime().exec(killCommand);
    p.waitFor();
    BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
    String line = "";
    LOG.info("Process output is ");
    while ((line = reader.readLine()) != null) {
      LOG.debug(line + "\n");
    }
  }

  public static String findPidCommand(String service) {
    return String.format("ps aux | grep %s | grep -v grep | tr -s ' ' | cut -d ' ' -f2", service);
  }

  public static String signalCommand(String service, String signal) {
    return String.format("%s | xargs kill -s %s", findPidCommand(service), signal);
  }

  public static File createSparkHome(File dataTestDir) throws IOException {
    File sparkDir = new File(new File(System.getProperty("user.dir"), "target"), "spark");
    if (!sparkDir.exists()) {
      throw new RuntimeException("'Cannot find spark assembly dir at location " + sparkDir.getAbsolutePath());
    }
    File sparkHome = new File(dataTestDir, "spark");
    System.setProperty(SPARK_TEST_HOME, sparkHome.getAbsolutePath());
    FileUtils.copyDirectory(sparkDir, sparkHome);
    return sparkHome;
  }

  public static MiniSDCTestingUtility getMiniSDCTestingUtility() {
    return miniSDCTestingUtility;
  }

  public static URI getServerURI() {
    return serverURI;
  }

  public static MiniSDC getMiniSDC() {
    return miniSDC;
  }

  public static MiniYARNCluster getMiniYarnCluster() {
    return miniYarnCluster;
  }
}
