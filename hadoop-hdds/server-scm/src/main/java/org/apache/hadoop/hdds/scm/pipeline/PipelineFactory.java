/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hdds.scm.pipeline;

import com.google.common.annotations.VisibleForTesting;
import org.apache.hadoop.hdds.client.ReplicationConfig;
import org.apache.hadoop.hdds.conf.ConfigurationSource;
import org.apache.hadoop.hdds.protocol.DatanodeDetails;
import org.apache.hadoop.hdds.protocol.proto.HddsProtos.ReplicationType;
import org.apache.hadoop.hdds.scm.ha.SCMContext;
import org.apache.hadoop.hdds.scm.node.NodeManager;
import org.apache.hadoop.hdds.server.events.EventPublisher;


import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Creates pipeline based on replication type.
 */
public class PipelineFactory {

  private Map<ReplicationType, PipelineProvider> providers;

  PipelineFactory(NodeManager nodeManager, PipelineStateManager stateManager,
                  ConfigurationSource conf, EventPublisher eventPublisher,
                  SCMContext scmContext) {
    providers = new HashMap<>();
    providers.put(ReplicationType.STAND_ALONE,
        new SimplePipelineProvider(nodeManager, stateManager));
    providers.put(ReplicationType.RATIS,
        new RatisPipelineProvider(nodeManager,
            stateManager, conf,
            eventPublisher, scmContext));
  }

  protected PipelineFactory() {
  }

  @VisibleForTesting
  void setProvider(
      ReplicationType replicationType,
      PipelineProvider provider
  ) {
    providers.put(replicationType, provider);
  }

  public Pipeline create(
      ReplicationConfig replicationConfig
  )
      throws IOException {
    return providers
        .get(replicationConfig.getReplicationType())
        .create(replicationConfig);
  }

  public Pipeline create(ReplicationConfig replicationConfig,
      List<DatanodeDetails> nodes
  ) {
    return providers.get(replicationConfig.getReplicationType())
        .create(replicationConfig, nodes);
  }

  public void close(ReplicationType type, Pipeline pipeline)
      throws IOException {
    providers.get(type).close(pipeline);
  }

  public void shutdown() {
    providers.values().forEach(provider -> provider.shutdown());
  }

  @VisibleForTesting
  public Map<ReplicationType, PipelineProvider> getProviders() {
    return providers;
  }

  protected void setProviders(
      Map<ReplicationType, PipelineProvider> providers) {
    this.providers = providers;
  }
}
