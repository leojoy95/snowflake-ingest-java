/*
 * Copyright (c) 2021 Snowflake Computing Inc. All rights reserved.
 */

package net.snowflake.ingest.streaming.internal;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import net.snowflake.client.core.HttpUtil;
import net.snowflake.client.core.OCSPMode;
import net.snowflake.client.jdbc.*;
import net.snowflake.client.jdbc.internal.apache.http.client.methods.HttpPost;
import net.snowflake.client.jdbc.internal.apache.http.client.utils.URIBuilder;
import net.snowflake.client.jdbc.internal.apache.http.entity.StringEntity;
import net.snowflake.client.jdbc.internal.fasterxml.jackson.databind.JsonNode;
import net.snowflake.client.jdbc.internal.fasterxml.jackson.databind.ObjectMapper;
import net.snowflake.client.jdbc.internal.fasterxml.jackson.databind.node.ObjectNode;

/** Handles uploading files to the Snowflake Streaming Ingest Stage */
public class StreamingIngestStage {
  private static final ObjectMapper mapper = new ObjectMapper();
  private static final long REFRESH_THRESHOLD_IN_MS =
      TimeUnit.MILLISECONDS.convert(1, TimeUnit.MINUTES);
  static final int MAX_RETRY_COUNT = 1;

  /**
   * Wrapper class containing SnowflakeFileTransferMetadata and the timestamp at which the metadata
   * was refreshed
   */
  static class SnowflakeFileTransferMetadataWithAge {
    SnowflakeFileTransferMetadataV1 fileTransferMetadata;

    /* Do do not always know the age of the metadata, so we use the empty
    state to record unknown age.
     */
    Optional<Long> timestamp;

    SnowflakeFileTransferMetadataWithAge(
        SnowflakeFileTransferMetadataV1 fileTransferMetadata, Optional<Long> timestamp) {
      this.fileTransferMetadata = fileTransferMetadata;
      this.timestamp = timestamp;
    }
  }

  private SnowflakeFileTransferMetadataWithAge fileTransferMetadataWithAge;
  private final SnowflakeURL snowflakeURL;

  public StreamingIngestStage(SnowflakeURL snowflakeURL) throws SnowflakeSQLException, IOException {
    this.snowflakeURL = snowflakeURL;
    this.refreshSnowflakeMetadata();
  }

  /**
   * Upload file to internal stage with previously cached credentials. Will refetch and cache
   * credentials if they've expired.
   *
   * @param fullFilePath Full file name to be uploaded
   * @param data Data string to be uploaded
   */
  public void putRemote(String fullFilePath, byte[] data)
      throws SnowflakeSQLException, IOException {
    this.putRemote(fullFilePath, data, 0);
  }

  private void putRemote(String fullFilePath, byte[] data, int retryCount)
      throws SnowflakeSQLException, IOException {
    // Set filename to be uploaded
    SnowflakeFileTransferMetadataV1 fileTransferMetadata =
        fileTransferMetadataWithAge.fileTransferMetadata;

    /*
    Since we can have multiple calls to putRemote in parallel and because the metadata includes the file path
    we use a copy for the upload to prevent us from using the wrong file path.
     */
    SnowflakeFileTransferMetadataV1 fileTransferMetadataCopy =
        new SnowflakeFileTransferMetadataV1(
            fileTransferMetadata.getPresignedUrl(),
            fullFilePath,
            fileTransferMetadata.getEncryptionMaterial() != null
                ? fileTransferMetadata.getEncryptionMaterial().getQueryStageMasterKey()
                : null,
            fileTransferMetadata.getEncryptionMaterial() != null
                ? fileTransferMetadata.getEncryptionMaterial().getQueryId()
                : null,
            fileTransferMetadata.getEncryptionMaterial() != null
                ? fileTransferMetadata.getEncryptionMaterial().getSmkId()
                : null,
            fileTransferMetadata.getCommandType(),
            fileTransferMetadata.getStageInfo());

    InputStream inStream = new ByteArrayInputStream(data);

    try {
      SnowflakeFileTransferAgent.uploadWithoutConnection(
          SnowflakeFileTransferConfig.Builder.newInstance()
              .setSnowflakeFileTransferMetadata(fileTransferMetadataCopy)
              .setUploadStream(inStream)
              .setRequireCompress(false)
              .setOcspMode(OCSPMode.FAIL_OPEN)
              .build());
    } catch (NullPointerException npe) {
      // TODO SNOW-350701 Update JDBC driver to throw a reliable token expired error
      if (retryCount >= MAX_RETRY_COUNT) {
        throw npe;
      }
      this.refreshSnowflakeMetadata();
      this.putRemote(fullFilePath, data, ++retryCount);
    } catch (Exception e) {
      throw new SnowflakeSQLException(e, ErrorCode.IO_ERROR);
    }
  }

  SnowflakeFileTransferMetadataWithAge refreshSnowflakeMetadata()
      throws SnowflakeSQLException, IOException {
    return refreshSnowflakeMetadata(false);
  }

  /**
   * Gets new stage credentials and other metadata from Snowflake. Synchronized to prevent multiple
   * calls to putRemote from trying to refresh at the same time
   *
   * @param force if true will ignore REFRESH_THRESHOLD and force metadata refresh
   * @return refreshed metadata
   * @throws SnowflakeSQLException
   * @throws IOException
   */
  synchronized SnowflakeFileTransferMetadataWithAge refreshSnowflakeMetadata(boolean force)
      throws SnowflakeSQLException, IOException {
    if (!force
        && fileTransferMetadataWithAge != null
        && fileTransferMetadataWithAge.timestamp.isPresent()
        && fileTransferMetadataWithAge.timestamp.get()
            > System.currentTimeMillis() - REFRESH_THRESHOLD_IN_MS) {
      return fileTransferMetadataWithAge;
    }

    // TODO Move to JWT/Oauth
    // TODO update configure url when we have new endpoint
    URI uri;
    try {
      uri =
          new URIBuilder()
              .setScheme(snowflakeURL.getScheme())
              .setHost(snowflakeURL.getUrlWithoutPort())
              .setPort(snowflakeURL.getPort())
              .setPath("v1/streaming/client/configure")
              .build();
    } catch (URISyntaxException e) {
      // TODO throw proper exception
      //      throw SnowflakeErrors.ERROR_6007.getException(e);
      throw new RuntimeException(e);
    }

    HttpPost postRequest = new HttpPost(uri);
    postRequest.addHeader("Accept", "application/json");

    StringEntity input = new StringEntity("{}", StandardCharsets.UTF_8);
    input.setContentType("application/json");
    postRequest.setEntity(input);

    String response = HttpUtil.executeGeneralRequest(postRequest, 60, null);
    JsonNode responseNode = mapper.readTree(response);

    // Currently have a few mismatches between the client/configure response and what
    // SnowflakeFileTransferAgent expects
    ObjectNode mutable = (ObjectNode) responseNode;
    mutable.putObject("data");
    ObjectNode dataNode = (ObjectNode) mutable.get("data");
    dataNode.set("stageInfo", responseNode.get("stage_location"));

    // JDBC expects this field which maps to presignedFileUrlName.  We override presignedFileUrlName
    // on each upload.
    dataNode.putArray("src_locations").add("placeholder");

    this.fileTransferMetadataWithAge =
        new SnowflakeFileTransferMetadataWithAge(
            (SnowflakeFileTransferMetadataV1)
                SnowflakeFileTransferAgent.getFileTransferMetadatas(responseNode).get(0),
            Optional.of(System.currentTimeMillis()));
    return this.fileTransferMetadataWithAge;
  }

  /**
   * ONLY FOR TESTING. Sets the age of the file transfer metadata
   *
   * @param timestamp new age in milliseconds
   */
  void setFileTransferMetadataAge(long timestamp) {
    this.fileTransferMetadataWithAge.timestamp = Optional.of(timestamp);
  }
}
