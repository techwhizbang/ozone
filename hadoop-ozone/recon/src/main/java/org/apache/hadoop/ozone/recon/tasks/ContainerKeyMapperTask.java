/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.ozone.recon.tasks;

import static org.apache.hadoop.ozone.om.OmMetadataManagerImpl.KEY_TABLE;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.hadoop.ozone.om.OMMetadataManager;
import org.apache.hadoop.ozone.om.helpers.BucketLayout;
import org.apache.hadoop.ozone.om.helpers.OmKeyInfo;
import org.apache.hadoop.ozone.om.helpers.OmKeyLocationInfo;
import org.apache.hadoop.ozone.om.helpers.OmKeyLocationInfoGroup;
import org.apache.hadoop.ozone.recon.api.types.ContainerKeyPrefix;
import org.apache.hadoop.ozone.recon.spi.ReconContainerMetadataManager;
import org.apache.hadoop.hdds.utils.db.Table;
import org.apache.hadoop.hdds.utils.db.TableIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Inject;

/**
 * Class to iterate over the OM DB and populate the Recon container DB with
 * the container -> Key reverse mapping.
 */
public class ContainerKeyMapperTask implements ReconOmTask {

  private static final Logger LOG =
      LoggerFactory.getLogger(ContainerKeyMapperTask.class);

  private ReconContainerMetadataManager reconContainerMetadataManager;

  @Inject
  public ContainerKeyMapperTask(ReconContainerMetadataManager
                                        reconContainerMetadataManager) {
    this.reconContainerMetadataManager = reconContainerMetadataManager;
  }

  /**
   * Read Key -> ContainerId data from OM snapshot DB and write reverse map
   * (container, key) -> count to Recon Container DB.
   */
  @Override
  public Pair<String, Boolean> reprocess(OMMetadataManager omMetadataManager) {
    long omKeyCount = 0;
    try {
      LOG.info("Starting a 'reprocess' run of ContainerKeyMapperTask.");
      Instant start = Instant.now();

      // initialize new container DB
      reconContainerMetadataManager
              .reinitWithNewContainerDataFromOm(new HashMap<>());

      Table<String, OmKeyInfo> omKeyInfoTable =
          omMetadataManager.getKeyTable(getBucketLayout());
      try (TableIterator<String, ? extends Table.KeyValue<String, OmKeyInfo>>
               keyIter = omKeyInfoTable.iterator()) {
        while (keyIter.hasNext()) {
          Table.KeyValue<String, OmKeyInfo> kv = keyIter.next();
          OmKeyInfo omKeyInfo = kv.getValue();
          writeOMKeyToContainerDB(kv.getKey(), omKeyInfo);
          omKeyCount++;
        }
      }
      LOG.info("Completed 'reprocess' of ContainerKeyMapperTask.");
      Instant end = Instant.now();
      long duration = Duration.between(start, end).toMillis();
      LOG.info("It took me {} seconds to process {} keys.",
          (double) duration / 1000.0, omKeyCount);
    } catch (IOException ioEx) {
      LOG.error("Unable to populate Container Key Prefix data in Recon DB. ",
          ioEx);
      return new ImmutablePair<>(getTaskName(), false);
    }
    return new ImmutablePair<>(getTaskName(), true);
  }

  @Override
  public String getTaskName() {
    return "ContainerKeyMapperTask";
  }

  public Collection<String> getTaskTables() {
    return Collections.singletonList(KEY_TABLE);
  }

  @Override
  public Pair<String, Boolean> process(OMUpdateEventBatch events) {
    Iterator<OMDBUpdateEvent> eventIterator = events.getIterator();
    int eventCount = 0;
    final Collection<String> taskTables = getTaskTables();

    while (eventIterator.hasNext()) {
      OMDBUpdateEvent<String, OmKeyInfo> omdbUpdateEvent = eventIterator.next();
      // Filter event inside process method to avoid duping
      if (!taskTables.contains(omdbUpdateEvent.getTable())) {
        continue;
      }
      String updatedKey = omdbUpdateEvent.getKey();
      OmKeyInfo updatedKeyValue = omdbUpdateEvent.getValue();
      try {
        switch (omdbUpdateEvent.getAction()) {
        case PUT:
          writeOMKeyToContainerDB(updatedKey, updatedKeyValue);
          break;

        case DELETE:
          deleteOMKeyFromContainerDB(updatedKey);
          break;

        case UPDATE:
          if (omdbUpdateEvent.getOldValue() != null) {
            deleteOMKeyFromContainerDB(
                omdbUpdateEvent.getOldValue().getKeyName());
          } else {
            LOG.warn("Update event does not have the old Key Info for {}.",
                updatedKey);
          }
          writeOMKeyToContainerDB(updatedKey, updatedKeyValue);
          break;

        default: LOG.debug("Skipping DB update event : {}",
            omdbUpdateEvent.getAction());
        }
        eventCount++;
      } catch (IOException e) {
        LOG.error("Unexpected exception while updating key data : {} ",
            updatedKey, e);
        return new ImmutablePair<>(getTaskName(), false);
      }
    }
    LOG.info("{} successfully processed {} OM DB update event(s).",
        getTaskName(), eventCount);
    return new ImmutablePair<>(getTaskName(), true);
  }

  /**
   * Delete an OM Key from Container DB and update containerID -> no. of keys
   * count.
   *
   * @param key key String.
   * @throws IOException If Unable to write to container DB.
   */
  private void  deleteOMKeyFromContainerDB(String key)
      throws IOException {

    TableIterator<ContainerKeyPrefix, ? extends
        Table.KeyValue<ContainerKeyPrefix, Integer>> containerIterator =
        reconContainerMetadataManager.getContainerTableIterator();

    Set<ContainerKeyPrefix> keysToBeDeleted = new HashSet<>();

    while (containerIterator.hasNext()) {
      Table.KeyValue<ContainerKeyPrefix, Integer> keyValue =
          containerIterator.next();
      String keyPrefix = keyValue.getKey().getKeyPrefix();
      if (keyPrefix.equals(key)) {
        keysToBeDeleted.add(keyValue.getKey());
      }
    }

    for (ContainerKeyPrefix containerKeyPrefix : keysToBeDeleted) {
      reconContainerMetadataManager.deleteContainerMapping(containerKeyPrefix);

      // decrement count and update containerKeyCount.
      Long containerID = containerKeyPrefix.getContainerId();
      long keyCount =
          reconContainerMetadataManager.getKeyCountForContainer(containerID);
      if (keyCount > 0) {
        reconContainerMetadataManager.storeContainerKeyCount(containerID,
            --keyCount);
      }
    }
  }

  /**
   * Write an OM key to container DB and update containerID -> no. of keys
   * count.
   *
   * @param key key String
   * @param omKeyInfo omKeyInfo value
   * @throws IOException if unable to write to recon DB.
   */
  private void  writeOMKeyToContainerDB(String key, OmKeyInfo omKeyInfo)
      throws IOException {
    long containerCountToIncrement = 0;
    for (OmKeyLocationInfoGroup omKeyLocationInfoGroup : omKeyInfo
        .getKeyLocationVersions()) {
      long keyVersion = omKeyLocationInfoGroup.getVersion();
      for (OmKeyLocationInfo omKeyLocationInfo : omKeyLocationInfoGroup
          .getLocationList()) {
        long containerId = omKeyLocationInfo.getContainerID();
        ContainerKeyPrefix containerKeyPrefix = new ContainerKeyPrefix(
            containerId, key, keyVersion);
        if (reconContainerMetadataManager.getCountForContainerKeyPrefix(
            containerKeyPrefix) == 0) {
          // Save on writes. No need to save same container-key prefix
          // mapping again.
          reconContainerMetadataManager.storeContainerKeyMapping(
              containerKeyPrefix, 1);

          // check if container already exists and
          // increment the count of containers if it does not exist
          if (!reconContainerMetadataManager.doesContainerExists(containerId)) {
            containerCountToIncrement++;
          }

          // update the count of keys for the given containerID
          long keyCount = reconContainerMetadataManager
                  .getKeyCountForContainer(containerId);

          // increment the count and update containerKeyCount.
          // keyCount will be 0 if containerID is not found. So, there is no
          // need to initialize keyCount for the first time.
          reconContainerMetadataManager.storeContainerKeyCount(containerId,
              ++keyCount);
        }
      }
    }

    if (containerCountToIncrement > 0) {
      reconContainerMetadataManager
          .incrementContainerCountBy(containerCountToIncrement);
    }
  }

  private BucketLayout getBucketLayout() {
    return BucketLayout.DEFAULT;
  }

}
