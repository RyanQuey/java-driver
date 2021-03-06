/*
 * Copyright DataStax, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.datastax.dse.driver.internal.core.graph;

import com.datastax.dse.driver.api.core.config.DseDriverOption;
import com.datastax.dse.driver.api.core.graph.AsyncGraphResultSet;
import com.datastax.dse.driver.api.core.graph.GraphNode;
import com.datastax.dse.driver.api.core.graph.GraphStatement;
import com.datastax.dse.driver.api.core.metrics.DseNodeMetric;
import com.datastax.dse.driver.api.core.metrics.DseSessionMetric;
import com.datastax.dse.driver.internal.core.cql.continuous.ContinuousRequestHandlerBase;
import com.datastax.dse.driver.internal.core.graph.binary.GraphBinaryModule;
import com.datastax.dse.protocol.internal.response.result.DseRowsMetadata;
import com.datastax.oss.driver.api.core.cql.ColumnDefinitions;
import com.datastax.oss.driver.api.core.cql.ExecutionInfo;
import com.datastax.oss.driver.internal.core.context.InternalDriverContext;
import com.datastax.oss.driver.internal.core.session.DefaultSession;
import com.datastax.oss.driver.shaded.guava.common.base.MoreObjects;
import com.datastax.oss.protocol.internal.Message;
import com.datastax.oss.protocol.internal.response.result.Rows;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import net.jcip.annotations.ThreadSafe;

/**
 * Handles a Graph request that supports multiple response messages (a.k.a. continuous paging
 * request).
 */
@ThreadSafe
public class ContinuousGraphRequestHandler
    extends ContinuousRequestHandlerBase<GraphStatement<?>, AsyncGraphResultSet> {

  private final Message message;
  private final GraphProtocol subProtocol;
  private final GraphBinaryModule graphBinaryModule;
  private final Duration globalTimeout;
  private final int maxEnqueuedPages;
  private final int maxPages;

  ContinuousGraphRequestHandler(
      @NonNull GraphStatement<?> statement,
      @NonNull DefaultSession session,
      @NonNull InternalDriverContext context,
      @NonNull String sessionLogPrefix,
      @NonNull GraphBinaryModule graphBinaryModule,
      @NonNull GraphSupportChecker graphSupportChecker) {
    super(
        statement,
        session,
        context,
        sessionLogPrefix,
        AsyncGraphResultSet.class,
        true,
        DseSessionMetric.GRAPH_CLIENT_TIMEOUTS,
        DseSessionMetric.GRAPH_REQUESTS,
        DseNodeMetric.GRAPH_MESSAGES);
    this.graphBinaryModule = graphBinaryModule;
    subProtocol = graphSupportChecker.inferGraphProtocol(statement, executionProfile, context);
    message =
        GraphConversions.createContinuousMessageFromGraphStatement(
            statement, subProtocol, executionProfile, context, graphBinaryModule);
    globalTimeout =
        MoreObjects.firstNonNull(
            statement.getTimeout(),
            executionProfile.getDuration(DseDriverOption.GRAPH_TIMEOUT, Duration.ZERO));
    maxEnqueuedPages =
        executionProfile.getInt(DseDriverOption.GRAPH_CONTINUOUS_PAGING_MAX_ENQUEUED_PAGES);
    maxPages = executionProfile.getInt(DseDriverOption.GRAPH_CONTINUOUS_PAGING_MAX_PAGES);
    // NOTE that ordering of the following statement matters.
    // We should register this request after all fields have been initialized.
    throttler.register(this);
  }

  @NonNull
  @Override
  protected Duration getGlobalTimeout() {
    return globalTimeout;
  }

  @NonNull
  @Override
  protected Duration getPageTimeout(int pageNumber) {
    return Duration.ZERO;
  }

  @NonNull
  @Override
  protected Duration getReviseRequestTimeout() {
    return Duration.ZERO;
  }

  @Override
  protected int getMaxEnqueuedPages() {
    return maxEnqueuedPages;
  }

  @Override
  protected int getMaxPages() {
    return maxPages;
  }

  @NonNull
  @Override
  protected Message getMessage() {
    return message;
  }

  @Override
  protected boolean isTracingEnabled() {
    return statement.isTracing();
  }

  @NonNull
  @Override
  protected Map<String, ByteBuffer> createPayload() {
    return GraphConversions.createCustomPayload(
        statement, subProtocol, executionProfile, context, graphBinaryModule);
  }

  @NonNull
  @Override
  protected AsyncGraphResultSet createEmptyResultSet(@NonNull ExecutionInfo executionInfo) {
    return ContinuousAsyncGraphResultSet.empty(executionInfo);
  }

  @NonNull
  @Override
  protected ContinuousAsyncGraphResultSet createResultSet(
      @NonNull Rows rows,
      @NonNull ExecutionInfo executionInfo,
      @NonNull ColumnDefinitions columnDefinitions)
      throws IOException {

    Queue<GraphNode> graphNodes = new ArrayDeque<>();
    for (List<ByteBuffer> row : rows.getData()) {
      if (subProtocol.isGraphBinary()) {
        graphNodes.offer(GraphConversions.createGraphBinaryGraphNode(row, this.graphBinaryModule));
      } else {
        graphNodes.offer(GraphSONUtils.createGraphNode(row, subProtocol));
      }
    }

    DseRowsMetadata metadata = (DseRowsMetadata) rows.getMetadata();
    return new ContinuousAsyncGraphResultSet(
        executionInfo,
        graphNodes,
        metadata.continuousPageNumber,
        !metadata.isLastContinuousPage,
        this,
        subProtocol);
  }

  @Override
  protected int pageNumber(@NonNull AsyncGraphResultSet resultSet) {
    return ((ContinuousAsyncGraphResultSet) resultSet).pageNumber();
  }
}
