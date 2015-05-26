/**
 * (c) 2015 StreamSets, Inc. All rights reserved. May not
 * be copied, modified, or distributed in whole or part without
 * written consent of StreamSets, Inc.
 */
package com.streamsets.pipeline.flume.standalone;

import com.google.common.io.Resources;
import com.streamsets.pipeline.base.TestPipelineRunStandalone;
import org.apache.flume.Channel;
import org.apache.flume.ChannelSelector;
import org.apache.flume.Context;
import org.apache.flume.Event;
import org.apache.flume.Transaction;
import org.apache.flume.channel.ChannelProcessor;
import org.apache.flume.channel.MemoryChannel;
import org.apache.flume.channel.ReplicatingChannelSelector;
import org.apache.flume.conf.Configurables;
import org.apache.flume.source.AvroSource;
import org.junit.After;
import org.junit.Before;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class TestFlumeDestinationPipelineRun extends TestPipelineRunStandalone {

  private static AvroSource source;
  private static Channel ch;


  @Before
  @Override
  public void setUp() throws Exception {
    super.setUp();
    //setup flume to write to
    source = new AvroSource();
    ch = new MemoryChannel();
    Configurables.configure(ch, new Context());

    Context context = new Context();
    //This should match whats present in the pipeline.json file
    context.put("port", String.valueOf(9050));
    context.put("bind", "localhost");
    Configurables.configure(source, context);

    List<Channel> channels = new ArrayList<>();
    channels.add(ch);
    ChannelSelector rcs = new ReplicatingChannelSelector();
    rcs.setChannels(channels);
    source.setChannelProcessor(new ChannelProcessor(rcs));
    source.start();
  }

  @After
  @Override
  public void tearDown() {
    source.stop();
    ch.stop();
  }

  @Override
  protected String getPipelineJson() throws Exception {
    URI uri = Resources.getResource("flume_destination_pipeline_run.json").toURI();
    String pipelineJson =  new String(Files.readAllBytes(Paths.get(uri)), StandardCharsets.UTF_8);
    return pipelineJson;
  }

  @Override
  protected int getRecordsInOrigin() {
    return 100;
  }

  @Override
  protected int getRecordsInTarget() throws IOException {
    int recordsRead = 0;

    Transaction transaction = ch.getTransaction();
    transaction.begin();
    Event event = ch.take();
    while(event != null) {
      recordsRead++;
      transaction.commit();
      transaction.close();

      transaction = ch.getTransaction();
      transaction.begin();
      event = ch.take();
    }

    return recordsRead;
  }
}
