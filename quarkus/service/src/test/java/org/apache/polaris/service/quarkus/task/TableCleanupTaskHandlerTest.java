/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.polaris.service.quarkus.task;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.apache.commons.codec.binary.Base64;
import org.apache.iceberg.ManifestFile;
import org.apache.iceberg.ManifestFiles;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.StatisticsFile;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.inmemory.InMemoryFileIO;
import org.apache.iceberg.io.FileIO;
import org.apache.polaris.core.PolarisConfigurationStore;
import org.apache.polaris.core.PolarisDiagnostics;
import org.apache.polaris.core.context.RealmContext;
import org.apache.polaris.core.entity.AsyncTaskType;
import org.apache.polaris.core.entity.PolarisBaseEntity;
import org.apache.polaris.core.entity.PolarisEntityType;
import org.apache.polaris.core.entity.TableLikeEntity;
import org.apache.polaris.core.entity.TaskEntity;
import org.apache.polaris.core.persistence.MetaStoreManagerFactory;
import org.apache.polaris.core.persistence.PolarisMetaStoreSession;
import org.apache.polaris.service.task.ManifestFileCleanupTaskHandler;
import org.apache.polaris.service.task.TableCleanupTaskHandler;
import org.apache.polaris.service.task.TaskUtils;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;

@QuarkusTest
class TableCleanupTaskHandlerTest {
  @Inject MetaStoreManagerFactory metaStoreManagerFactory;
  @Inject PolarisConfigurationStore configurationStore;
  @Inject PolarisDiagnostics diagnostics;

  private final RealmContext realmContext = () -> "realmName";

  @Test
  public void testTableCleanup() throws IOException {
    PolarisMetaStoreSession metaStoreSession =
        metaStoreManagerFactory.getOrCreateSessionSupplier(realmContext).get();
    FileIO fileIO = new InMemoryFileIO();
    TableIdentifier tableIdentifier = TableIdentifier.of(Namespace.of("db1", "schema1"), "table1");
    TableCleanupTaskHandler handler =
        new TableCleanupTaskHandler(
            Mockito.mock(),
            metaStoreManagerFactory,
            configurationStore,
            diagnostics,
            (task, rc) -> fileIO,
            Clock.systemUTC());
    long snapshotId = 100L;
    ManifestFile manifestFile =
        TaskTestUtils.manifestFile(
            fileIO, "manifest1.avro", snapshotId, "dataFile1.parquet", "dataFile2.parquet");
    TestSnapshot snapshot =
        TaskTestUtils.newSnapshot(fileIO, "manifestList.avro", 1, snapshotId, 99L, manifestFile);
    String metadataFile = "v1-49494949.metadata.json";
    StatisticsFile statisticsFile =
        TaskTestUtils.writeStatsFile(
            snapshot.snapshotId(),
            snapshot.sequenceNumber(),
            "/metadata/" + UUID.randomUUID() + ".stats",
            fileIO);
    TaskTestUtils.writeTableMetadata(fileIO, metadataFile, List.of(statisticsFile), snapshot);

    TaskEntity task =
        new TaskEntity.Builder()
            .setName("cleanup_" + tableIdentifier)
            .withTaskType(diagnostics, AsyncTaskType.ENTITY_CLEANUP_SCHEDULER)
            .withData(
                diagnostics,
                new TableLikeEntity.Builder(tableIdentifier, metadataFile)
                    .setName("table1")
                    .setCatalogId(1)
                    .setCreateTimestamp(100)
                    .build())
            .build();
    Assertions.assertThatPredicate(handler::canHandleTask).accepts(task);

    handler.handleTask(task, realmContext);

    assertThat(
            metaStoreManagerFactory
                .getOrCreateMetaStoreManager(realmContext)
                .loadTasks(metaStoreSession, "test", 2)
                .getEntities())
        .hasSize(2)
        .satisfiesExactlyInAnyOrder(
            taskEntity ->
                assertThat(taskEntity)
                    .returns(PolarisEntityType.TASK.getCode(), PolarisBaseEntity::getTypeCode)
                    .extracting(TaskEntity::of)
                    .returns(
                        AsyncTaskType.MANIFEST_FILE_CLEANUP,
                        taskEntity1 -> taskEntity1.getTaskType(diagnostics))
                    .returns(
                        new ManifestFileCleanupTaskHandler.ManifestCleanupTask(
                            tableIdentifier,
                            Base64.encodeBase64String(ManifestFiles.encode(manifestFile))),
                        entity ->
                            entity.readData(
                                diagnostics,
                                ManifestFileCleanupTaskHandler.ManifestCleanupTask.class)),
            taskEntity ->
                assertThat(taskEntity)
                    .returns(PolarisEntityType.TASK.getCode(), PolarisBaseEntity::getTypeCode)
                    .extracting(TaskEntity::of)
                    .returns(
                        AsyncTaskType.METADATA_FILE_BATCH_CLEANUP,
                        taskEntity2 -> taskEntity2.getTaskType(diagnostics))
                    .returns(
                        new ManifestFileCleanupTaskHandler.ManifestCleanupTask(
                            tableIdentifier, List.of(statisticsFile.path())),
                        entity ->
                            entity.readData(
                                diagnostics,
                                ManifestFileCleanupTaskHandler.ManifestCleanupTask.class)));
  }

  @Test
  public void testTableCleanupHandlesAlreadyDeletedMetadata() throws IOException {
    PolarisMetaStoreSession metaStoreSession =
        metaStoreManagerFactory.getOrCreateSessionSupplier(realmContext).get();
    FileIO fileIO =
        new InMemoryFileIO() {
          @Override
          public void close() {
            // no-op
          }
        };
    TableIdentifier tableIdentifier = TableIdentifier.of(Namespace.of("db1", "schema1"), "table1");
    TableCleanupTaskHandler handler =
        new TableCleanupTaskHandler(
            Mockito.mock(),
            metaStoreManagerFactory,
            configurationStore,
            diagnostics,
            (task, rc) -> fileIO,
            Clock.systemUTC());
    long snapshotId = 100L;
    ManifestFile manifestFile =
        TaskTestUtils.manifestFile(
            fileIO, "manifest1.avro", snapshotId, "dataFile1.parquet", "dataFile2.parquet");
    TestSnapshot snapshot =
        TaskTestUtils.newSnapshot(fileIO, "manifestList.avro", 1, snapshotId, 99L, manifestFile);
    String metadataFile = "v1-49494949.metadata.json";
    TaskTestUtils.writeTableMetadata(fileIO, metadataFile, snapshot);

    TableLikeEntity tableLikeEntity =
        new TableLikeEntity.Builder(tableIdentifier, metadataFile)
            .setName("table1")
            .setCatalogId(1)
            .setCreateTimestamp(100)
            .build();
    TaskEntity task =
        new TaskEntity.Builder()
            .setName("cleanup_" + tableIdentifier)
            .withTaskType(diagnostics, AsyncTaskType.ENTITY_CLEANUP_SCHEDULER)
            .withData(diagnostics, tableLikeEntity)
            .build();
    Assertions.assertThatPredicate(handler::canHandleTask).accepts(task);

    // handle the same task twice
    // the first one should successfully delete the metadata
    List<Boolean> results =
        List.of(handler.handleTask(task, realmContext), handler.handleTask(task, realmContext));
    assertThat(results).containsExactly(true, true);

    // both tasks successfully executed, but only one should queue subtasks
    assertThat(
            metaStoreManagerFactory
                .getOrCreateMetaStoreManager(realmContext)
                .loadTasks(metaStoreSession, "test", 5)
                .getEntities())
        .hasSize(1);
  }

  @Test
  public void testTableCleanupDuplicatesTasksIfFileStillExists() throws IOException {
    PolarisMetaStoreSession metaStoreSession =
        metaStoreManagerFactory.getOrCreateSessionSupplier(realmContext).get();
    FileIO fileIO =
        new InMemoryFileIO() {
          @Override
          public void deleteFile(String location) {
            LoggerFactory.getLogger(TableCleanupTaskHandler.class)
                .info(
                    "Not deleting file at location {} to simulate concurrent tasks runs", location);
            // don't do anything
          }

          @Override
          public void close() {
            // no-op
          }
        };
    TableIdentifier tableIdentifier = TableIdentifier.of(Namespace.of("db1", "schema1"), "table1");
    TableCleanupTaskHandler handler =
        new TableCleanupTaskHandler(
            Mockito.mock(),
            metaStoreManagerFactory,
            configurationStore,
            diagnostics,
            (task, rc) -> fileIO,
            Clock.systemUTC());
    long snapshotId = 100L;
    ManifestFile manifestFile =
        TaskTestUtils.manifestFile(
            fileIO, "manifest1.avro", snapshotId, "dataFile1.parquet", "dataFile2.parquet");
    TestSnapshot snapshot =
        TaskTestUtils.newSnapshot(fileIO, "manifestList.avro", 1, snapshotId, 99L, manifestFile);
    String metadataFile = "v1-49494949.metadata.json";
    TaskTestUtils.writeTableMetadata(fileIO, metadataFile, snapshot);

    TaskEntity task =
        new TaskEntity.Builder()
            .setName("cleanup_" + tableIdentifier)
            .withTaskType(diagnostics, AsyncTaskType.ENTITY_CLEANUP_SCHEDULER)
            .withData(
                diagnostics,
                new TableLikeEntity.Builder(tableIdentifier, metadataFile)
                    .setName("table1")
                    .setCatalogId(1)
                    .setCreateTimestamp(100)
                    .build())
            .build();
    Assertions.assertThatPredicate(handler::canHandleTask).accepts(task);

    // handle the same task twice
    // the first one should successfully delete the metadata
    List<Boolean> results =
        List.of(handler.handleTask(task, realmContext), handler.handleTask(task, realmContext));
    assertThat(results).containsExactly(true, true);

    // both tasks successfully executed, but only one should queue subtasks
    assertThat(
            metaStoreManagerFactory
                .getOrCreateMetaStoreManager(realmContext)
                .loadTasks(metaStoreSession, "test", 5)
                .getEntities())
        .hasSize(2)
        .satisfiesExactly(
            taskEntity ->
                assertThat(taskEntity)
                    .returns(PolarisEntityType.TASK.getCode(), PolarisBaseEntity::getTypeCode)
                    .extracting(TaskEntity::of)
                    .returns(
                        AsyncTaskType.MANIFEST_FILE_CLEANUP,
                        taskEntity1 -> taskEntity1.getTaskType(diagnostics))
                    .returns(
                        new ManifestFileCleanupTaskHandler.ManifestCleanupTask(
                            tableIdentifier,
                            Base64.encodeBase64String(ManifestFiles.encode(manifestFile))),
                        entity ->
                            entity.readData(
                                diagnostics,
                                ManifestFileCleanupTaskHandler.ManifestCleanupTask.class)),
            taskEntity ->
                assertThat(taskEntity)
                    .returns(PolarisEntityType.TASK.getCode(), PolarisBaseEntity::getTypeCode)
                    .extracting(TaskEntity::of)
                    .returns(
                        AsyncTaskType.MANIFEST_FILE_CLEANUP,
                        taskEntity2 -> taskEntity2.getTaskType(diagnostics))
                    .returns(
                        new ManifestFileCleanupTaskHandler.ManifestCleanupTask(
                            tableIdentifier,
                            Base64.encodeBase64String(ManifestFiles.encode(manifestFile))),
                        entity ->
                            entity.readData(
                                diagnostics,
                                ManifestFileCleanupTaskHandler.ManifestCleanupTask.class)));
  }

  @Test
  public void testTableCleanupMultipleSnapshots() throws IOException {
    PolarisMetaStoreSession metaStoreSession =
        metaStoreManagerFactory.getOrCreateSessionSupplier(realmContext).get();
    FileIO fileIO = new InMemoryFileIO();
    TableIdentifier tableIdentifier = TableIdentifier.of(Namespace.of("db1", "schema1"), "table1");
    TableCleanupTaskHandler handler =
        new TableCleanupTaskHandler(
            Mockito.mock(),
            metaStoreManagerFactory,
            configurationStore,
            diagnostics,
            (task, rc) -> fileIO,
            Clock.systemUTC());
    long snapshotId1 = 100L;
    ManifestFile manifestFile1 =
        TaskTestUtils.manifestFile(
            fileIO, "manifest1.avro", snapshotId1, "dataFile1.parquet", "dataFile2.parquet");
    ManifestFile manifestFile2 =
        TaskTestUtils.manifestFile(
            fileIO, "manifest2.avro", snapshotId1, "dataFile3.parquet", "dataFile4.parquet");
    Snapshot snapshot =
        TaskTestUtils.newSnapshot(
            fileIO, "manifestList.avro", 1, snapshotId1, 99L, manifestFile1, manifestFile2);
    ManifestFile manifestFile3 =
        TaskTestUtils.manifestFile(
            fileIO, "manifest3.avro", snapshot.snapshotId() + 1, "dataFile5.parquet");
    Snapshot snapshot2 =
        TaskTestUtils.newSnapshot(
            fileIO,
            "manifestList2.avro",
            snapshot.sequenceNumber() + 1,
            snapshot.snapshotId() + 1,
            snapshot.snapshotId(),
            manifestFile1,
            manifestFile3); // exclude manifest2 from the new snapshot
    String metadataFile = "v1-295495059.metadata.json";
    StatisticsFile statisticsFile1 =
        TaskTestUtils.writeStatsFile(
            snapshot.snapshotId(),
            snapshot.sequenceNumber(),
            "/metadata/" + UUID.randomUUID() + ".stats",
            fileIO);
    StatisticsFile statisticsFile2 =
        TaskTestUtils.writeStatsFile(
            snapshot2.snapshotId(),
            snapshot2.sequenceNumber(),
            "/metadata/" + UUID.randomUUID() + ".stats",
            fileIO);
    TaskTestUtils.writeTableMetadata(
        fileIO, metadataFile, List.of(statisticsFile1, statisticsFile2), snapshot, snapshot2);

    TaskEntity task =
        new TaskEntity.Builder()
            .withTaskType(diagnostics, AsyncTaskType.ENTITY_CLEANUP_SCHEDULER)
            .withData(
                diagnostics,
                new TableLikeEntity.Builder(tableIdentifier, metadataFile)
                    .setName("table1")
                    .setCatalogId(1)
                    .setCreateTimestamp(100)
                    .build())
            .build();
    Assertions.assertThatPredicate(handler::canHandleTask).accepts(task);

    handler.handleTask(task, realmContext);

    List<PolarisBaseEntity> entities =
        metaStoreManagerFactory
            .getOrCreateMetaStoreManager(realmContext)
            .loadTasks(metaStoreSession, "test", 5)
            .getEntities();

    List<PolarisBaseEntity> manifestCleanupTasks =
        entities.stream()
            .filter(
                entity -> {
                  AsyncTaskType taskType = TaskEntity.of(entity).getTaskType(diagnostics);
                  return taskType == AsyncTaskType.MANIFEST_FILE_CLEANUP;
                })
            .toList();
    List<PolarisBaseEntity> metadataCleanupTasks =
        entities.stream()
            .filter(
                entity -> {
                  AsyncTaskType taskType = TaskEntity.of(entity).getTaskType(diagnostics);
                  return taskType == AsyncTaskType.METADATA_FILE_BATCH_CLEANUP;
                })
            .toList();

    assertThat(metadataCleanupTasks)
        .hasSize(1)
        .satisfiesExactlyInAnyOrder(
            taskEntity ->
                assertThat(taskEntity)
                    .returns(PolarisEntityType.TASK.getCode(), PolarisBaseEntity::getTypeCode)
                    .extracting(TaskEntity::of)
                    .returns(
                        new ManifestFileCleanupTaskHandler.ManifestCleanupTask(
                            tableIdentifier,
                            List.of(statisticsFile1.path(), statisticsFile2.path())),
                        entity ->
                            entity.readData(
                                diagnostics,
                                ManifestFileCleanupTaskHandler.ManifestCleanupTask.class)));

    assertThat(manifestCleanupTasks)
        // all three manifests should be present, even though one is excluded from the latest
        // snapshot
        .hasSize(3)
        .satisfiesExactlyInAnyOrder(
            taskEntity ->
                assertThat(taskEntity)
                    .returns(PolarisEntityType.TASK.getCode(), PolarisBaseEntity::getTypeCode)
                    .extracting(TaskEntity::of)
                    .returns(
                        new ManifestFileCleanupTaskHandler.ManifestCleanupTask(
                            tableIdentifier,
                            Base64.encodeBase64String(ManifestFiles.encode(manifestFile1))),
                        entity ->
                            entity.readData(
                                diagnostics,
                                ManifestFileCleanupTaskHandler.ManifestCleanupTask.class)),
            taskEntity ->
                assertThat(taskEntity)
                    .returns(PolarisEntityType.TASK.getCode(), PolarisBaseEntity::getTypeCode)
                    .extracting(TaskEntity::of)
                    .returns(
                        new ManifestFileCleanupTaskHandler.ManifestCleanupTask(
                            tableIdentifier,
                            Base64.encodeBase64String(ManifestFiles.encode(manifestFile2))),
                        entity ->
                            entity.readData(
                                diagnostics,
                                ManifestFileCleanupTaskHandler.ManifestCleanupTask.class)),
            taskEntity ->
                assertThat(taskEntity)
                    .returns(PolarisEntityType.TASK.getCode(), PolarisBaseEntity::getTypeCode)
                    .extracting(TaskEntity::of)
                    .returns(
                        new ManifestFileCleanupTaskHandler.ManifestCleanupTask(
                            tableIdentifier,
                            Base64.encodeBase64String(ManifestFiles.encode(manifestFile3))),
                        entity ->
                            entity.readData(
                                diagnostics,
                                ManifestFileCleanupTaskHandler.ManifestCleanupTask.class)));
  }

  @Test
  public void testTableCleanupMultipleMetadata() throws IOException {
    PolarisMetaStoreSession metaStoreSession =
        metaStoreManagerFactory.getOrCreateSessionSupplier(realmContext).get();
    FileIO fileIO = new InMemoryFileIO();
    TableIdentifier tableIdentifier = TableIdentifier.of(Namespace.of("db1", "schema1"), "table1");
    TableCleanupTaskHandler handler =
        new TableCleanupTaskHandler(
            Mockito.mock(),
            metaStoreManagerFactory,
            configurationStore,
            diagnostics,
            (task, rc) -> fileIO,
            Clock.systemUTC());
    long snapshotId1 = 100L;
    ManifestFile manifestFile1 =
        TaskTestUtils.manifestFile(
            fileIO, "manifest1.avro", snapshotId1, "dataFile1.parquet", "dataFile2.parquet");
    ManifestFile manifestFile2 =
        TaskTestUtils.manifestFile(
            fileIO, "manifest2.avro", snapshotId1, "dataFile3.parquet", "dataFile4.parquet");
    Snapshot snapshot =
        TaskTestUtils.newSnapshot(
            fileIO, "manifestList.avro", 1, snapshotId1, 99L, manifestFile1, manifestFile2);
    StatisticsFile statisticsFile1 =
        TaskTestUtils.writeStatsFile(
            snapshot.snapshotId(),
            snapshot.sequenceNumber(),
            "/metadata/" + UUID.randomUUID() + ".stats",
            fileIO);
    String firstMetadataFile = "v1-295495059.metadata.json";
    TableMetadata firstMetadata =
        TaskTestUtils.writeTableMetadata(
            fileIO, firstMetadataFile, List.of(statisticsFile1), snapshot);
    assertThat(TaskUtils.exists(firstMetadataFile, fileIO)).isTrue();

    ManifestFile manifestFile3 =
        TaskTestUtils.manifestFile(
            fileIO, "manifest3.avro", snapshot.snapshotId() + 1, "dataFile5.parquet");
    Snapshot snapshot2 =
        TaskTestUtils.newSnapshot(
            fileIO,
            "manifestList2.avro",
            snapshot.sequenceNumber() + 1,
            snapshot.snapshotId() + 1,
            snapshot.snapshotId(),
            manifestFile1,
            manifestFile3); // exclude manifest2 from the new snapshot
    StatisticsFile statisticsFile2 =
        TaskTestUtils.writeStatsFile(
            snapshot2.snapshotId(),
            snapshot2.sequenceNumber(),
            "/metadata/" + UUID.randomUUID() + ".stats",
            fileIO);
    String secondMetadataFile = "v1-295495060.metadata.json";
    TaskTestUtils.writeTableMetadata(
        fileIO,
        secondMetadataFile,
        firstMetadata,
        firstMetadataFile,
        List.of(statisticsFile2),
        snapshot2);
    assertThat(TaskUtils.exists(firstMetadataFile, fileIO)).isTrue();
    assertThat(TaskUtils.exists(secondMetadataFile, fileIO)).isTrue();

    TaskEntity task =
        new TaskEntity.Builder()
            .withTaskType(diagnostics, AsyncTaskType.ENTITY_CLEANUP_SCHEDULER)
            .withData(
                diagnostics,
                new TableLikeEntity.Builder(tableIdentifier, secondMetadataFile)
                    .setName("table1")
                    .setCatalogId(1)
                    .setCreateTimestamp(100)
                    .build())
            .build();

    Assertions.assertThatPredicate(handler::canHandleTask).accepts(task);

    handler.handleTask(task, realmContext);

    List<PolarisBaseEntity> entities =
        metaStoreManagerFactory
            .getOrCreateMetaStoreManager(realmContext)
            .loadTasks(metaStoreSession, "test", 6)
            .getEntities();

    List<PolarisBaseEntity> manifestCleanupTasks =
        entities.stream()
            .filter(
                entity -> {
                  AsyncTaskType taskType = TaskEntity.of(entity).getTaskType(diagnostics);
                  return taskType == AsyncTaskType.MANIFEST_FILE_CLEANUP;
                })
            .toList();
    List<PolarisBaseEntity> metadataCleanupTasks =
        entities.stream()
            .filter(
                entity -> {
                  AsyncTaskType taskType = TaskEntity.of(entity).getTaskType(diagnostics);
                  return taskType == AsyncTaskType.METADATA_FILE_BATCH_CLEANUP;
                })
            .toList();

    assertThat(metadataCleanupTasks)
        .hasSize(1)
        .satisfiesExactlyInAnyOrder(
            taskEntity ->
                assertThat(taskEntity)
                    .returns(PolarisEntityType.TASK.getCode(), PolarisBaseEntity::getTypeCode)
                    .extracting(TaskEntity::of)
                    .returns(
                        new ManifestFileCleanupTaskHandler.ManifestCleanupTask(
                            tableIdentifier,
                            List.of(
                                firstMetadataFile, statisticsFile1.path(), statisticsFile2.path())),
                        entity ->
                            entity.readData(
                                diagnostics,
                                ManifestFileCleanupTaskHandler.ManifestCleanupTask.class)));

    assertThat(manifestCleanupTasks)
        .hasSize(3)
        .satisfiesExactlyInAnyOrder(
            taskEntity ->
                assertThat(taskEntity)
                    .returns(PolarisEntityType.TASK.getCode(), PolarisBaseEntity::getTypeCode)
                    .extracting(TaskEntity::of)
                    .returns(
                        new ManifestFileCleanupTaskHandler.ManifestCleanupTask(
                            tableIdentifier,
                            Base64.encodeBase64String(ManifestFiles.encode(manifestFile1))),
                        entity ->
                            entity.readData(
                                diagnostics,
                                ManifestFileCleanupTaskHandler.ManifestCleanupTask.class)),
            taskEntity ->
                assertThat(taskEntity)
                    .returns(PolarisEntityType.TASK.getCode(), PolarisBaseEntity::getTypeCode)
                    .extracting(TaskEntity::of)
                    .returns(
                        new ManifestFileCleanupTaskHandler.ManifestCleanupTask(
                            tableIdentifier,
                            Base64.encodeBase64String(ManifestFiles.encode(manifestFile2))),
                        entity ->
                            entity.readData(
                                diagnostics,
                                ManifestFileCleanupTaskHandler.ManifestCleanupTask.class)),
            taskEntity ->
                assertThat(taskEntity)
                    .returns(PolarisEntityType.TASK.getCode(), PolarisBaseEntity::getTypeCode)
                    .extracting(TaskEntity::of)
                    .returns(
                        new ManifestFileCleanupTaskHandler.ManifestCleanupTask(
                            tableIdentifier,
                            Base64.encodeBase64String(ManifestFiles.encode(manifestFile3))),
                        entity ->
                            entity.readData(
                                diagnostics,
                                ManifestFileCleanupTaskHandler.ManifestCleanupTask.class)));
  }
}
