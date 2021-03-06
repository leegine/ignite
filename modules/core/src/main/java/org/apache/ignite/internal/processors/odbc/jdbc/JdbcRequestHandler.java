/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.processors.odbc.jdbc;

import java.sql.BatchUpdateException;
import java.sql.ParameterMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.SortedSet;
import java.util.concurrent.ConcurrentHashMap;
import javax.cache.configuration.Factory;
import org.apache.ignite.IgniteCheckedException;
import org.apache.ignite.IgniteLogger;
import org.apache.ignite.cache.query.BulkLoadContextCursor;
import org.apache.ignite.cache.query.FieldsQueryCursor;
import org.apache.ignite.cache.query.QueryCancelledException;
import org.apache.ignite.internal.GridKernalContext;
import org.apache.ignite.internal.IgniteInterruptedCheckedException;
import org.apache.ignite.internal.IgniteVersionUtils;
import org.apache.ignite.internal.binary.BinaryWriterExImpl;
import org.apache.ignite.internal.processors.authentication.AuthorizationContext;
import org.apache.ignite.internal.processors.bulkload.BulkLoadAckClientParameters;
import org.apache.ignite.internal.processors.bulkload.BulkLoadProcessor;
import org.apache.ignite.internal.processors.cache.QueryCursorImpl;
import org.apache.ignite.internal.processors.cache.mvcc.MvccUtils;
import org.apache.ignite.internal.processors.cache.query.IgniteQueryErrorCode;
import org.apache.ignite.internal.processors.cache.query.SqlFieldsQueryEx;
import org.apache.ignite.internal.processors.odbc.ClientListenerProtocolVersion;
import org.apache.ignite.internal.processors.odbc.ClientListenerRequest;
import org.apache.ignite.internal.processors.odbc.ClientListenerRequestHandler;
import org.apache.ignite.internal.processors.odbc.ClientListenerResponse;
import org.apache.ignite.internal.processors.odbc.ClientListenerResponseSender;
import org.apache.ignite.internal.processors.query.GridQueryCancel;
import org.apache.ignite.internal.processors.query.IgniteSQLException;
import org.apache.ignite.internal.processors.query.NestedTxMode;
import org.apache.ignite.internal.processors.query.QueryUtils;
import org.apache.ignite.internal.processors.query.SqlClientContext;
import org.apache.ignite.internal.util.GridSpinBusyLock;
import org.apache.ignite.internal.util.future.GridFutureAdapter;
import org.apache.ignite.internal.util.typedef.F;
import org.apache.ignite.internal.util.typedef.X;
import org.apache.ignite.internal.util.typedef.internal.S;
import org.apache.ignite.internal.util.typedef.internal.U;
import org.apache.ignite.internal.util.worker.GridWorker;
import org.apache.ignite.lang.IgniteBiTuple;
import org.apache.ignite.transactions.TransactionAlreadyCompletedException;
import org.apache.ignite.transactions.TransactionDuplicateKeyException;
import org.apache.ignite.transactions.TransactionSerializationException;

import static org.apache.ignite.internal.processors.odbc.jdbc.JdbcBulkLoadBatchRequest.CMD_CONTINUE;
import static org.apache.ignite.internal.processors.odbc.jdbc.JdbcBulkLoadBatchRequest.CMD_FINISHED_EOF;
import static org.apache.ignite.internal.processors.odbc.jdbc.JdbcBulkLoadBatchRequest.CMD_FINISHED_ERROR;
import static org.apache.ignite.internal.processors.odbc.jdbc.JdbcConnectionContext.VER_2_3_0;
import static org.apache.ignite.internal.processors.odbc.jdbc.JdbcConnectionContext.VER_2_4_0;
import static org.apache.ignite.internal.processors.odbc.jdbc.JdbcConnectionContext.VER_2_7_0;
import static org.apache.ignite.internal.processors.odbc.jdbc.JdbcConnectionContext.VER_2_8_0;
import static org.apache.ignite.internal.processors.odbc.jdbc.JdbcRequest.BATCH_EXEC;
import static org.apache.ignite.internal.processors.odbc.jdbc.JdbcRequest.BATCH_EXEC_ORDERED;
import static org.apache.ignite.internal.processors.odbc.jdbc.JdbcRequest.BULK_LOAD_BATCH;
import static org.apache.ignite.internal.processors.odbc.jdbc.JdbcRequest.META_COLUMNS;
import static org.apache.ignite.internal.processors.odbc.jdbc.JdbcRequest.META_INDEXES;
import static org.apache.ignite.internal.processors.odbc.jdbc.JdbcRequest.META_PARAMS;
import static org.apache.ignite.internal.processors.odbc.jdbc.JdbcRequest.META_PRIMARY_KEYS;
import static org.apache.ignite.internal.processors.odbc.jdbc.JdbcRequest.META_SCHEMAS;
import static org.apache.ignite.internal.processors.odbc.jdbc.JdbcRequest.META_TABLES;
import static org.apache.ignite.internal.processors.odbc.jdbc.JdbcRequest.QRY_CANCEL;
import static org.apache.ignite.internal.processors.odbc.jdbc.JdbcRequest.QRY_CLOSE;
import static org.apache.ignite.internal.processors.odbc.jdbc.JdbcRequest.QRY_EXEC;
import static org.apache.ignite.internal.processors.odbc.jdbc.JdbcRequest.QRY_FETCH;
import static org.apache.ignite.internal.processors.odbc.jdbc.JdbcRequest.QRY_META;

/**
 * JDBC request handler.
 */
public class JdbcRequestHandler implements ClientListenerRequestHandler {
    /** Jdbc query cancelled response. */
    private static final JdbcResponse JDBC_QUERY_CANCELLED_RESPONSE =
        new JdbcResponse(IgniteQueryErrorCode.QUERY_CANCELED, QueryCancelledException.ERR_MSG);

    /** Kernel context. */
    private final GridKernalContext ctx;

    /** Client context. */
    private final SqlClientContext cliCtx;

    /** Logger. */
    private final IgniteLogger log;

    /** Busy lock. */
    private final GridSpinBusyLock busyLock;

    /** Worker. */
    private final JdbcRequestHandlerWorker worker;

    /** Maximum allowed cursors. */
    private final int maxCursors;

    /** Current JDBC cursors. */
    private final ConcurrentHashMap<Long, JdbcCursor> jdbcCursors = new ConcurrentHashMap<>();

    /** Ordered batches queue. */
    private final PriorityQueue<JdbcOrderedBatchExecuteRequest> orderedBatchesQueue = new PriorityQueue<>();

    /** Ordered batches mutex. */
    private final Object orderedBatchesMux = new Object();

    /** Request mutex. */
    private final Object reqMux = new Object();

    /** Response sender. */
    private final ClientListenerResponseSender sender;

    /** Automatic close of cursors. */
    private final boolean autoCloseCursors;

    /** Nested transactions handling mode. */
    private final NestedTxMode nestedTxMode;

    /** Protocol version. */
    private final ClientListenerProtocolVersion protocolVer;

    /** Authentication context */
    private AuthorizationContext actx;

    /** Facade that hides transformations internal cache api entities -> jdbc metadata. */
    private final JdbcMetadataInfo meta;

    /** Register that keeps non-cancelled requests. */
    private Map<Long, JdbcQueryDescriptor> reqRegister = new HashMap<>();

    /**
     * Constructor.
     * @param ctx Context.
     * @param busyLock Shutdown latch.
     * @param sender Results sender.
     * @param maxCursors Maximum allowed cursors.
     * @param distributedJoins Distributed joins flag.
     * @param enforceJoinOrder Enforce join order flag.
     * @param collocated Collocated flag.
     * @param replicatedOnly Replicated only flag.
     * @param autoCloseCursors Flag to automatically close server cursors.
     * @param lazy Lazy query execution flag.
     * @param skipReducerOnUpdate Skip reducer on update flag.
     * @param actx Authentication context.
     * @param protocolVer Protocol version.
     */
    public JdbcRequestHandler(GridKernalContext ctx, GridSpinBusyLock busyLock,
        ClientListenerResponseSender sender, int maxCursors,
        boolean distributedJoins, boolean enforceJoinOrder, boolean collocated, boolean replicatedOnly,
        boolean autoCloseCursors, boolean lazy, boolean skipReducerOnUpdate, NestedTxMode nestedTxMode,
        AuthorizationContext actx, ClientListenerProtocolVersion protocolVer) {
        this.ctx = ctx;
        this.sender = sender;

        this.meta = new JdbcMetadataInfo(ctx);

        Factory<GridWorker> orderedFactory = new Factory<GridWorker>() {
            @Override public GridWorker create() {
                return new OrderedBatchWorker();
            }
        };

        this.cliCtx = new SqlClientContext(
            ctx,
            orderedFactory,
            distributedJoins,
            enforceJoinOrder,
            collocated,
            replicatedOnly,
            lazy,
            skipReducerOnUpdate
        );

        this.busyLock = busyLock;
        this.maxCursors = maxCursors;
        this.autoCloseCursors = autoCloseCursors;
        this.nestedTxMode = nestedTxMode;
        this.protocolVer = protocolVer;
        this.actx = actx;

        log = ctx.log(getClass());

        // TODO IGNITE-9484 Do not create worker if there is a possibility to unbind TX from threads.
        worker = new JdbcRequestHandlerWorker(ctx.igniteInstanceName(), log, this, ctx);
    }

    /** {@inheritDoc} */
    @Override public ClientListenerResponse handle(ClientListenerRequest req0) {
        assert req0 != null;

        assert req0 instanceof JdbcRequest;

        JdbcRequest req = (JdbcRequest)req0;

        if (!MvccUtils.mvccEnabled(ctx))
            return doHandle(req);
        else {
            GridFutureAdapter<ClientListenerResponse> fut = worker.process(req);

            try {
                return fut.get();
            }
            catch (IgniteCheckedException e) {
                return exceptionToResult(e);
            }
        }
    }

    /** {@inheritDoc} */
    @Override public boolean isCancellationCommand(int cmdId) {
        return cmdId == JdbcRequest.QRY_CANCEL;
    }

    /** {@inheritDoc} */
    @Override public void registerRequest(long reqId, int cmdType) {
        assert reqId != 0;

        synchronized (reqMux) {
            if (isCancellationSupported() && (cmdType == QRY_EXEC || cmdType == BATCH_EXEC ||
                cmdType == BATCH_EXEC_ORDERED))
                reqRegister.put(reqId, new JdbcQueryDescriptor());
        }
    }

    /** {@inheritDoc} */
    @Override public void unregisterRequest(long reqId) {
        assert reqId != 0;

        synchronized (reqMux) {
            if (isCancellationSupported())
                reqRegister.remove(reqId);
        }
    }

    /**
     * Start worker, if it's present.
     */
    void start() {
        if (worker != null)
            worker.start();
    }

    /**
     * Actually handle the request.
     * @param req Request.
     * @return Request handling result.
     */
    ClientListenerResponse doHandle(JdbcRequest req) {
        if (!busyLock.enterBusy())
            return new JdbcResponse(IgniteQueryErrorCode.UNKNOWN,
                "Failed to handle JDBC request because node is stopping.");

        if (actx != null)
            AuthorizationContext.context(actx);

        try {
            switch (req.type()) {
                case QRY_EXEC:
                    return executeQuery((JdbcQueryExecuteRequest)req);

                case QRY_FETCH:
                    return fetchQuery((JdbcQueryFetchRequest)req);

                case QRY_CLOSE:
                    return closeQuery((JdbcQueryCloseRequest)req);

                case QRY_META:
                    return getQueryMeta((JdbcQueryMetadataRequest)req);

                case BATCH_EXEC:
                    return executeBatch((JdbcBatchExecuteRequest)req);

                case BATCH_EXEC_ORDERED:
                    return dispatchBatchOrdered((JdbcOrderedBatchExecuteRequest)req);

                case META_TABLES:
                    return getTablesMeta((JdbcMetaTablesRequest)req);

                case META_COLUMNS:
                    return getColumnsMeta((JdbcMetaColumnsRequest)req);

                case META_INDEXES:
                    return getIndexesMeta((JdbcMetaIndexesRequest)req);

                case META_PARAMS:
                    return getParametersMeta((JdbcMetaParamsRequest)req);

                case META_PRIMARY_KEYS:
                    return getPrimaryKeys((JdbcMetaPrimaryKeysRequest)req);

                case META_SCHEMAS:
                    return getSchemas((JdbcMetaSchemasRequest)req);

                case BULK_LOAD_BATCH:
                    return processBulkLoadFileBatch((JdbcBulkLoadBatchRequest)req);

                case QRY_CANCEL:
                    return cancelQuery((JdbcQueryCancelRequest)req);
            }

            return new JdbcResponse(IgniteQueryErrorCode.UNSUPPORTED_OPERATION,
                "Unsupported JDBC request [req=" + req + ']');
        }
        finally {
            AuthorizationContext.clear();

            busyLock.leaveBusy();
        }
    }

    /**
     * @param req Ordered batch request.
     * @return Response.
     */
    private ClientListenerResponse dispatchBatchOrdered(JdbcOrderedBatchExecuteRequest req) {
        synchronized (orderedBatchesMux) {
            orderedBatchesQueue.add(req);

            orderedBatchesMux.notify();
        }

        if (!cliCtx.isStreamOrdered())
            executeBatchOrdered(req);

        return null;
    }

    /**
     * @param req Ordered batch request.
     * @return Response.
     */
    private ClientListenerResponse executeBatchOrdered(JdbcOrderedBatchExecuteRequest req) {
        try {
            if (req.isLastStreamBatch())
                cliCtx.waitTotalProcessedOrderedRequests(req.order());

            JdbcResponse resp = (JdbcResponse)executeBatch(req);

            if (resp.response() instanceof JdbcBatchExecuteResult) {
                resp = new JdbcResponse(
                    new JdbcOrderedBatchExecuteResult((JdbcBatchExecuteResult)resp.response(), req.order()));
            }

            sender.send(resp);
        } catch (Exception e) {
            U.error(null, "Error processing file batch", e);

            sender.send(new JdbcResponse(IgniteQueryErrorCode.UNKNOWN, "Server error: " + e));
        }

        synchronized (orderedBatchesMux) {
            orderedBatchesQueue.poll();
        }

        cliCtx.orderedRequestProcessed();

        return null;
    }

    /**
     * Processes a file batch sent from client as part of bulk load COPY command.
     *
     * @param req Request object with a batch of a file received from client.
     * @return Response to send to the client.
     */
    private ClientListenerResponse processBulkLoadFileBatch(JdbcBulkLoadBatchRequest req) {
        if (ctx == null)
            return new JdbcResponse(IgniteQueryErrorCode.UNEXPECTED_OPERATION, "Unknown query ID: "
                + req.cursorId() + ". Bulk load session may have been reclaimed due to timeout.");

        JdbcBulkLoadProcessor processor = (JdbcBulkLoadProcessor)jdbcCursors.get(req.cursorId());

        if (!prepareQueryCancellationMeta(processor))
            return JDBC_QUERY_CANCELLED_RESPONSE;

        boolean unregisterReq = false;

        try {
            processor.processBatch(req);

            switch (req.cmd()) {
                case CMD_FINISHED_ERROR:
                case CMD_FINISHED_EOF:
                    jdbcCursors.remove(req.cursorId());

                    processor.close();

                    unregisterReq = true;

                    break;

                case CMD_CONTINUE:
                    break;

                default:
                    throw new IllegalArgumentException();
            }
            return new JdbcResponse(new JdbcQueryExecuteResult(req.cursorId(), processor.updateCnt()));
        }
        catch (Exception e) {
            U.error(null, "Error processing file batch", e);

            if (X.cause(e, QueryCancelledException.class) != null)
                return exceptionToResult(new QueryCancelledException());
            else
                return new JdbcResponse(IgniteQueryErrorCode.UNKNOWN, "Server error: " + e);
        }
        finally {
            cleanupQueryCancellationMeta(unregisterReq, processor.requestId());
        }
    }

    /** {@inheritDoc} */
    @Override public ClientListenerResponse handleException(Exception e, ClientListenerRequest req) {
        return exceptionToResult(e);
    }

    /** {@inheritDoc} */
    @Override public void writeHandshake(BinaryWriterExImpl writer) {
        // Handshake OK.
        writer.writeBoolean(true);

        // Write server version.
        writer.writeByte(IgniteVersionUtils.VER.major());
        writer.writeByte(IgniteVersionUtils.VER.minor());
        writer.writeByte(IgniteVersionUtils.VER.maintenance());
        writer.writeString(IgniteVersionUtils.VER.stage());
        writer.writeLong(IgniteVersionUtils.VER.revisionTimestamp());
        writer.writeByteArray(IgniteVersionUtils.VER.revisionHash());
    }

    /**
     * Called whenever client is disconnected due to correct connection close
     * or due to {@code IOException} during network operations.
     */
    public void onDisconnect() {
        if (worker != null) {
            worker.cancel();

            try {
                worker.join();
            }
            catch (InterruptedException e) {
                // No-op.
            }
        }

        for (JdbcCursor cursor : jdbcCursors.values())
            U.close(cursor, log);

        jdbcCursors.clear();

        synchronized (reqMux) {
            reqRegister.clear();
        }

        U.close(cliCtx, log);
    }

    /**
     * {@link JdbcQueryExecuteRequest} command handler.
     *
     * @param req Execute query request.
     * @return Response.
     */
    @SuppressWarnings("unchecked")
    private JdbcResponse executeQuery(JdbcQueryExecuteRequest req) {
        GridQueryCancel cancel = null;

        boolean unregisterReq = false;

        if (isCancellationSupported()) {
            synchronized (reqMux) {
                JdbcQueryDescriptor desc = reqRegister.get(req.requestId());

                // Query was already cancelled and unregistered.
                if (desc == null)
                    return null;

                cancel = desc.cancelHook();

                desc.incrementUsageCount();
            }
        }

        try {
            int cursorCnt = jdbcCursors.size();

            if (maxCursors > 0 && cursorCnt >= maxCursors)
                return new JdbcResponse(IgniteQueryErrorCode.UNKNOWN, "Too many open cursors (either close other " +
                    "open cursors or increase the limit through " +
                    "ClientConnectorConfiguration.maxOpenCursorsPerConnection) [maximum=" + maxCursors +
                    ", current=" + cursorCnt + ']');

            assert !cliCtx.isStream();

            String sql = req.sqlQuery();

            SqlFieldsQueryEx qry;

            switch (req.expectedStatementType()) {
                case ANY_STATEMENT_TYPE:
                    qry = new SqlFieldsQueryEx(sql, null);

                    break;

                case SELECT_STATEMENT_TYPE:
                    qry = new SqlFieldsQueryEx(sql, true);

                    break;

                default:
                    assert req.expectedStatementType() == JdbcStatementType.UPDATE_STMT_TYPE;

                    qry = new SqlFieldsQueryEx(sql, false);

                    if (cliCtx.isSkipReducerOnUpdate())
                        ((SqlFieldsQueryEx)qry).setSkipReducerOnUpdate(true);
            }

            qry.setArgs(req.arguments());

            qry.setDistributedJoins(cliCtx.isDistributedJoins());
            qry.setEnforceJoinOrder(cliCtx.isEnforceJoinOrder());
            qry.setCollocated(cliCtx.isCollocated());
            qry.setReplicatedOnly(cliCtx.isReplicatedOnly());
            qry.setLazy(cliCtx.isLazy());
            qry.setNestedTxMode(nestedTxMode);
            qry.setAutoCommit(req.autoCommit());

            if (req.pageSize() <= 0)
                return new JdbcResponse(IgniteQueryErrorCode.UNKNOWN, "Invalid fetch size: " + req.pageSize());

            qry.setPageSize(req.pageSize());

            String schemaName = req.schemaName();

            if (F.isEmpty(schemaName))
                schemaName = QueryUtils.DFLT_SCHEMA;

            qry.setSchema(schemaName);

            List<FieldsQueryCursor<List<?>>> results = ctx.query().querySqlFields(null, qry, cliCtx, true,
                protocolVer.compareTo(VER_2_3_0) < 0, cancel);

            FieldsQueryCursor<List<?>> fieldsCur = results.get(0);

            if (fieldsCur instanceof BulkLoadContextCursor) {
                BulkLoadContextCursor blCur = (BulkLoadContextCursor)fieldsCur;

                BulkLoadProcessor blProcessor = blCur.bulkLoadProcessor();
                BulkLoadAckClientParameters clientParams = blCur.clientParams();

                JdbcBulkLoadProcessor processor = new JdbcBulkLoadProcessor(blProcessor, req.requestId());

                jdbcCursors.put(processor.cursorId(), processor);

                // responses for the same query on the client side
                return new JdbcResponse(new JdbcBulkLoadAckResult(processor.cursorId(), clientParams));
            }

            if (results.size() == 1) {
                JdbcQueryCursor cur = new JdbcQueryCursor(req.pageSize(), req.maxRows(),
                    (QueryCursorImpl)fieldsCur, req.requestId());

                jdbcCursors.put(cur.cursorId(), cur);

                cur.openIterator();

                JdbcQueryExecuteResult res;

                if (cur.isQuery())
                    res = new JdbcQueryExecuteResult(cur.cursorId(), cur.fetchRows(), !cur.hasNext());
                else {
                    List<List<Object>> items = cur.fetchRows();

                    assert items != null && items.size() == 1 && items.get(0).size() == 1
                        && items.get(0).get(0) instanceof Long :
                        "Invalid result set for not-SELECT query. [qry=" + sql +
                            ", res=" + S.toString(List.class, items) + ']';

                    res = new JdbcQueryExecuteResult(cur.cursorId(), (Long)items.get(0).get(0));
                }

                if (res.last() && (!res.isQuery() || autoCloseCursors)) {
                    jdbcCursors.remove(cur.cursorId());

                    unregisterReq = true;

                    cur.close();
                }

                return new JdbcResponse(res);
            }
            else {
                List<JdbcResultInfo> jdbcResults = new ArrayList<>(results.size());
                List<List<Object>> items = null;
                boolean last = true;

                for (FieldsQueryCursor<List<?>> c : results) {
                    QueryCursorImpl qryCur = (QueryCursorImpl)c;

                    JdbcResultInfo jdbcRes;

                    if (qryCur.isQuery()) {
                        JdbcQueryCursor cur = new JdbcQueryCursor(req.pageSize(), req.maxRows(), qryCur, req.requestId());

                        jdbcCursors.put(cur.cursorId(), cur);

                        jdbcRes = new JdbcResultInfo(true, -1, cur.cursorId());

                        cur.openIterator();

                        if (items == null) {
                            items = cur.fetchRows();
                            last = cur.hasNext();
                        }
                    }
                    else
                        jdbcRes = new JdbcResultInfo(false, (Long)((List<?>)qryCur.getAll().get(0)).get(0), -1);

                    jdbcResults.add(jdbcRes);
                }

                return new JdbcResponse(new JdbcQueryExecuteMultipleStatementsResult(jdbcResults, items, last));
            }
        }
        catch (Exception e) {
            // Trying to close all cursors of current request.
            clearCursors(req.requestId());

            unregisterReq = true;

            U.error(log, "Failed to execute SQL query [reqId=" + req.requestId() + ", req=" + req + ']', e);

            if (X.cause(e, QueryCancelledException.class) != null)
                return exceptionToResult(new QueryCancelledException());
            else
                return exceptionToResult(e);
        }
        finally {
            cleanupQueryCancellationMeta(unregisterReq, req.requestId());
        }
    }

    /**
     * {@link JdbcQueryCloseRequest} command handler.
     *
     * @param req Execute query request.
     * @return Response.
     */
    private JdbcResponse closeQuery(JdbcQueryCloseRequest req) {
        JdbcCursor cur = jdbcCursors.get(req.cursorId());

        if (!prepareQueryCancellationMeta(cur))
            return new JdbcResponse(null);

        try {
            cur = jdbcCursors.remove(req.cursorId());

            if (cur == null)
                return new JdbcResponse(IgniteQueryErrorCode.UNKNOWN,
                    "Failed to find query cursor with ID: " + req.cursorId());

            cur.close();

            return new JdbcResponse(null);
        }
        catch (Exception e) {
            jdbcCursors.remove(req.cursorId());

            U.error(log, "Failed to close SQL query [reqId=" + req.requestId() + ", req=" + req + ']', e);

            if (X.cause(e, QueryCancelledException.class) != null)
                return new JdbcResponse(null);
            else
                return exceptionToResult(e);
        }
        finally {
            if (isCancellationSupported()) {
                boolean clearCursors = false;

                synchronized (reqMux) {
                    assert cur != null;

                    JdbcQueryDescriptor desc = reqRegister.get(cur.requestId());

                    if (desc != null) {
                        // Query was cancelled during execution.
                        if (desc.isCanceled()) {
                            clearCursors = true;

                            unregisterRequest(req.requestId());
                        }
                        else {
                            tryUnregisterRequest(cur.requestId());

                            desc.decrementUsageCount();
                        }
                    }
                }

                if (clearCursors)
                    clearCursors(cur.requestId());
            }
        }
    }

    /**
     * {@link JdbcQueryFetchRequest} command handler.
     *
     * @param req Execute query request.
     * @return Response.
     */
    private JdbcResponse fetchQuery(JdbcQueryFetchRequest req) {
        final JdbcQueryCursor cur = (JdbcQueryCursor)jdbcCursors.get(req.cursorId());

        if (!prepareQueryCancellationMeta(cur))
            return JDBC_QUERY_CANCELLED_RESPONSE;

        boolean unregisterReq = false;

        try {
            if (cur == null)
                return new JdbcResponse(IgniteQueryErrorCode.UNKNOWN,
                    "Failed to find query cursor with ID: " + req.cursorId());

            if (req.pageSize() <= 0)
                return new JdbcResponse(IgniteQueryErrorCode.UNKNOWN,
                    "Invalid fetch size : [fetchSize=" + req.pageSize() + ']');

            cur.pageSize(req.pageSize());

            JdbcQueryFetchResult res = new JdbcQueryFetchResult(cur.fetchRows(), !cur.hasNext());

            if (res.last() && (!cur.isQuery() || autoCloseCursors)) {
                jdbcCursors.remove(req.cursorId());

                unregisterReq = true;

                cur.close();
            }

            return new JdbcResponse(res);
        }
        catch (Exception e) {
            U.error(log, "Failed to fetch SQL query result [reqId=" + req.requestId() + ", req=" + req + ']', e);

            if (X.cause(e, QueryCancelledException.class) != null)
                return exceptionToResult(new QueryCancelledException());
            else
                return exceptionToResult(e);
        }
        finally {
            assert cur != null;

            cleanupQueryCancellationMeta(unregisterReq, cur.requestId());
        }
    }

    /**
     * @param req Request.
     * @return Response.
     */
    private JdbcResponse getQueryMeta(JdbcQueryMetadataRequest req) {
        final JdbcQueryCursor cur = (JdbcQueryCursor)jdbcCursors.get(req.cursorId());

        if (!prepareQueryCancellationMeta(cur))
            return JDBC_QUERY_CANCELLED_RESPONSE;

        try {
            if (cur == null)
                return new JdbcResponse(IgniteQueryErrorCode.UNKNOWN,
                    "Failed to find query cursor with ID: " + req.cursorId());

            JdbcQueryMetadataResult res = new JdbcQueryMetadataResult(req.cursorId(),
                cur.meta());

            return new JdbcResponse(res);
        }
        catch (Exception e) {
            U.error(log, "Failed to fetch SQL query result [reqId=" + req.requestId() + ", req=" + req + ']', e);

            return exceptionToResult(e);
        }
        finally {
            assert cur != null;

            cleanupQueryCancellationMeta(false, cur.requestId());
        }
    }

    /**
     * @param req Request.
     * @return Response.
     */
    private ClientListenerResponse executeBatch(JdbcBatchExecuteRequest req) {
        GridQueryCancel cancel = null;

        if (isCancellationSupported()) {
            synchronized (reqMux) {
                JdbcQueryDescriptor desc = reqRegister.get(req.requestId());

                // Query was already cancelled and unregisterd.
                if (desc == null)
                    return null;

                cancel = desc.cancelHook();

                desc.incrementUsageCount();
            }
        }

        try {
            String schemaName = req.schemaName();

            if (F.isEmpty(schemaName))
                schemaName = QueryUtils.DFLT_SCHEMA;

            int qryCnt = req.queries().size();

            List<Integer> updCntsAcc = new ArrayList<>(qryCnt);

            // Send back only the first error. Others will be written to the log.
            IgniteBiTuple<Integer, String> firstErr = new IgniteBiTuple<>();

            SqlFieldsQueryEx qry = null;

            for (JdbcQuery q : req.queries()) {
                if (q.sql() != null) { // If we have a new query string in the batch,
                    if (qry != null) // then execute the previous sub-batch and create a new SqlFieldsQueryEx.
                        executeBatchedQuery(qry, updCntsAcc, firstErr, cancel);

                    qry = new SqlFieldsQueryEx(q.sql(), false);

                    qry.setDistributedJoins(cliCtx.isDistributedJoins());
                    qry.setEnforceJoinOrder(cliCtx.isEnforceJoinOrder());
                    qry.setCollocated(cliCtx.isCollocated());
                    qry.setReplicatedOnly(cliCtx.isReplicatedOnly());
                    qry.setLazy(cliCtx.isLazy());
                    qry.setNestedTxMode(nestedTxMode);
                    qry.setAutoCommit(req.autoCommit());

                    qry.setSchema(schemaName);
                }

                assert qry != null;

                qry.addBatchedArgs(q.args());
            }

            if (qry != null)
                executeBatchedQuery(qry, updCntsAcc, firstErr, cancel);

            if (req.isLastStreamBatch())
                cliCtx.disableStreaming();

            int updCnts[] = U.toIntArray(updCntsAcc);

            if (firstErr.isEmpty())
                return new JdbcResponse(new JdbcBatchExecuteResult(updCnts, ClientListenerResponse.STATUS_SUCCESS,
                    null));
            else
                return new JdbcResponse(new JdbcBatchExecuteResult(updCnts, firstErr.getKey(), firstErr.getValue()));
        }
        catch (QueryCancelledException e) {
            return exceptionToResult(e);
        }
        finally {
            cleanupQueryCancellationMeta(true, req.requestId());
        }
    }

    /**
     * Executes query and updates result counters.
     *
     * @param qry Query.
     * @param updCntsAcc Per query rows updates counter.
     * @param firstErr First error data - code and message.
     * @param cancel Hook for query cancellation.
     * @throws QueryCancelledException If query was cancelled during execution.
     */
    @SuppressWarnings({"ForLoopReplaceableByForEach"})
    private void executeBatchedQuery(SqlFieldsQueryEx qry, List<Integer> updCntsAcc,
        IgniteBiTuple<Integer, String> firstErr, GridQueryCancel cancel) throws QueryCancelledException {
        try {
            if (cliCtx.isStream()) {
                List<Long> cnt = ctx.query().streamBatchedUpdateQuery(
                    qry.getSchema(),
                    cliCtx,
                    qry.getSql(),
                    qry.batchedArguments()
                );

                for (int i = 0; i < cnt.size(); i++)
                    updCntsAcc.add(cnt.get(i).intValue());

                return;
            }

            List<FieldsQueryCursor<List<?>>> qryRes = ctx.query().querySqlFields(null, qry, cliCtx, true, true, cancel);

            for (FieldsQueryCursor<List<?>> cur : qryRes) {
                if (cur instanceof BulkLoadContextCursor)
                    throw new IgniteSQLException("COPY command cannot be executed in batch mode.");

                assert !((QueryCursorImpl)cur).isQuery();

                Iterator<List<?>> it = cur.iterator();

                if (it.hasNext()) {
                    int val = ((Long)it.next().get(0)).intValue();

                    updCntsAcc.add(val);
                }
            }
        }
        catch (Exception e) {
            int code;

            String msg;

            if (X.cause(e, QueryCancelledException.class) != null)
                throw new QueryCancelledException();
            else if (e instanceof IgniteSQLException) {
                BatchUpdateException batchCause = X.cause(e, BatchUpdateException.class);

                if (batchCause != null) {
                    int[] updCntsOnErr = batchCause.getUpdateCounts();

                    for (int i = 0; i < updCntsOnErr.length; i++)
                        updCntsAcc.add(updCntsOnErr[i]);

                    msg = batchCause.getMessage();

                    code = batchCause.getErrorCode();
                }
                else {
                    for (int i = 0; i < qry.batchedArguments().size(); i++)
                        updCntsAcc.add(Statement.EXECUTE_FAILED);

                    msg = e.getMessage();

                    code = ((IgniteSQLException)e).statusCode();
                }
            }
            else {
                for (int i = 0; i < qry.batchedArguments().size(); i++)
                    updCntsAcc.add(Statement.EXECUTE_FAILED);

                msg = e.getMessage();

                code = IgniteQueryErrorCode.UNKNOWN;
            }

            if (firstErr.isEmpty())
                firstErr.set(code, msg);
            else
                U.error(log, "Failed to execute batch query [qry=" + qry + ']', e);
        }
    }

    /**
     * @param req Get tables metadata request.
     * @return Response.
     */
    private JdbcResponse getTablesMeta(JdbcMetaTablesRequest req) {
        try {
            List<JdbcTableMeta> tabMetas = meta.getTablesMeta(req.schemaName(), req.tableName());

            JdbcMetaTablesResult res = new JdbcMetaTablesResult(tabMetas);

            return new JdbcResponse(res);
        }
        catch (Exception e) {
            U.error(log, "Failed to get tables metadata [reqId=" + req.requestId() + ", req=" + req + ']', e);

            return exceptionToResult(e);
        }
    }

    /**
     * @param req Get columns metadata request.
     * @return Response.
     */
    private JdbcResponse getColumnsMeta(JdbcMetaColumnsRequest req) {
        try {
            Collection<JdbcColumnMeta> colsMeta =
                meta.getColumnsMeta(protocolVer, req.schemaName(), req.tableName(), req.columnName());

            JdbcMetaColumnsResult res;

            if (protocolVer.compareTo(VER_2_7_0) >= 0)
                res = new JdbcMetaColumnsResultV4(colsMeta);
            else if (protocolVer.compareTo(VER_2_4_0) >= 0)
                res = new JdbcMetaColumnsResultV3(colsMeta);
            else if (protocolVer.compareTo(VER_2_3_0) >= 0)
                res = new JdbcMetaColumnsResultV2(colsMeta);
            else
                res = new JdbcMetaColumnsResult(colsMeta);

            return new JdbcResponse(res);
        }
        catch (Exception e) {
            U.error(log, "Failed to get columns metadata [reqId=" + req.requestId() + ", req=" + req + ']', e);

            return exceptionToResult(e);
        }
    }

    /**
     * @param req Request.
     * @return Response.
     */
    private ClientListenerResponse getIndexesMeta(JdbcMetaIndexesRequest req) {
        try {
            Collection<JdbcIndexMeta> idxInfos = meta.getIndexesMeta(req.schemaName(), req.tableName());

            return new JdbcResponse(new JdbcMetaIndexesResult(idxInfos));
        }
        catch (Exception e) {
            U.error(log, "Failed to get parameters metadata [reqId=" + req.requestId() + ", req=" + req + ']', e);

            return exceptionToResult(e);
        }
    }

    /**
     * @param req Request.
     * @return Response.
     */
    private ClientListenerResponse getParametersMeta(JdbcMetaParamsRequest req) {
        try {
            ParameterMetaData paramMeta = ctx.query().prepareNativeStatement(req.schemaName(), req.sql())
                .getParameterMetaData();

            int size = paramMeta.getParameterCount();

            List<JdbcParameterMeta> meta = new ArrayList<>(size);

            for (int i = 0; i < size; i++)
                meta.add(new JdbcParameterMeta(paramMeta, i + 1));

            JdbcMetaParamsResult res = new JdbcMetaParamsResult(meta);

            return new JdbcResponse(res);
        }
        catch (Exception e) {
            U.error(log, "Failed to get parameters metadata [reqId=" + req.requestId() + ", req=" + req + ']', e);

            return exceptionToResult(e);
        }
    }

    /**
     * @param req Request.
     * @return Response.
     */
    private ClientListenerResponse getPrimaryKeys(JdbcMetaPrimaryKeysRequest req) {
        try {
            Collection<JdbcPrimaryKeyMeta> pkMeta = meta.getPrimaryKeys(req.schemaName(), req.tableName());

            return new JdbcResponse(new JdbcMetaPrimaryKeysResult(pkMeta));
        }
        catch (Exception e) {
            U.error(log, "Failed to get parameters metadata [reqId=" + req.requestId() + ", req=" + req + ']', e);

            return exceptionToResult(e);
        }
    }

    /**
     * @param req Request.
     * @return Response.
     */
    private ClientListenerResponse getSchemas(JdbcMetaSchemasRequest req) {
        try {
            String schemaPtrn = req.schemaName();

            SortedSet<String> schemas = meta.getSchemasMeta(schemaPtrn);

            return new JdbcResponse(new JdbcMetaSchemasResult(schemas));
        }
        catch (Exception e) {
            U.error(log, "Failed to get schemas metadata [reqId=" + req.requestId() + ", req=" + req + ']', e);

            return exceptionToResult(e);
        }
    }

    /**
     * Create {@link JdbcResponse} bearing appropriate Ignite specific result code if possible
     *     from given {@link Exception}.
     *
     * @param e Exception to convert.
     * @return resulting {@link JdbcResponse}.
     */
    private JdbcResponse exceptionToResult(Exception e) {
        if (e instanceof QueryCancelledException)
            return new JdbcResponse(IgniteQueryErrorCode.QUERY_CANCELED, e.getMessage());
        if (e instanceof TransactionSerializationException)
            return new JdbcResponse(IgniteQueryErrorCode.TRANSACTION_SERIALIZATION_ERROR, e.getMessage());
        if (e instanceof TransactionAlreadyCompletedException)
            return new JdbcResponse(IgniteQueryErrorCode.TRANSACTION_COMPLETED, e.getMessage());
        if (e instanceof TransactionDuplicateKeyException)
            return new JdbcResponse(IgniteQueryErrorCode.DUPLICATE_KEY, e.getMessage());
        if (e instanceof MvccUtils.NonMvccTransactionException)
            return new JdbcResponse(IgniteQueryErrorCode.TRANSACTION_TYPE_MISMATCH, e.getMessage());
        if (e instanceof MvccUtils.UnsupportedTxModeException)
            return new JdbcResponse(IgniteQueryErrorCode.UNSUPPORTED_OPERATION, e.getMessage());
        if (e instanceof IgniteSQLException)
            return new JdbcResponse(((IgniteSQLException)e).statusCode(), e.getMessage());
        else
            return new JdbcResponse(IgniteQueryErrorCode.UNKNOWN, e.getMessage());
    }

    /**
     * Ordered batch worker.
     */
    private class OrderedBatchWorker extends GridWorker {
        /**
         * Constructor.
         */
        OrderedBatchWorker() {
            super(ctx.igniteInstanceName(), "ordered-batch", JdbcRequestHandler.this.log);
        }

        /** {@inheritDoc} */
        @Override protected void body() throws InterruptedException, IgniteInterruptedCheckedException {
            long nextBatchOrder = 0;

            while (true) {
                if (!cliCtx.isStream())
                    return;

                JdbcOrderedBatchExecuteRequest req;

                synchronized (orderedBatchesMux) {
                    req = orderedBatchesQueue.peek();

                    if (req == null || req.order() != nextBatchOrder) {
                        orderedBatchesMux.wait();

                        continue;
                    }
                }

                executeBatchOrdered(req);

                nextBatchOrder++;
            }
        }
    }

    /**
     * Cancels query with specified request id;
     *
     * @param req Query cancellation request;
     * @return <code>QueryCancelledException</code> wrapped with <code>JdbcResponse</code>
     */
    private JdbcResponse cancelQuery(JdbcQueryCancelRequest req) {
        boolean clearCursors = false;

        GridQueryCancel cancelHook;

        synchronized (reqMux) {
            JdbcQueryDescriptor desc = reqRegister.get(req.requestIdToBeCancelled());

            // Query was already executed.
            if (desc == null)
                return null;

            // Query was registered, however execution didn't start yet.
            else if (!desc.isExecutionStarted()) {
                unregisterRequest(req.requestId());

                return exceptionToResult(new QueryCancelledException());
            }
            else {
                cancelHook = desc.cancelHook();

                desc.markCancelled();

                if (desc.usageCount() == 0) {
                    clearCursors = true;

                    unregisterRequest(req.requestIdToBeCancelled());
                }
            }
        }

        cancelHook.cancel();

        if (clearCursors)
            clearCursors(req.requestIdToBeCancelled());

        return null;
    }

    /**
     * Checks whether query cancellation is supported whithin given version of protocal.
     *
     * @return True if supported, false otherwise.
     */
    private boolean isCancellationSupported() {
        return (protocolVer.compareTo(VER_2_8_0) >= 0);
    }

    /**
     * Unregisters request if there are no cursors binded to it.
     *
     * @param reqId Reuest to unregist.
     */
    private void tryUnregisterRequest(long reqId) {
        assert isCancellationSupported();

        boolean unregisterReq = true;

        for (JdbcCursor cursor : jdbcCursors.values()) {
            if (cursor.requestId() == reqId) {
                unregisterReq = false;

                break;
            }
        }

        if (unregisterReq)
            unregisterRequest(reqId);
    }

    /**
     * Tries to close all cursors of request with given id and removes them from jdbcCursors map.
     *
     * @param reqId Request ID.
     */
    private void clearCursors(long reqId) {
        for (Iterator<Map.Entry<Long, JdbcCursor>> it = jdbcCursors.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Long, JdbcCursor> entry = it.next();

            JdbcCursor cursor = entry.getValue();

            if (cursor.requestId() == reqId) {
                try {
                    cursor.close();
                }
                catch (Exception e) {
                    U.error(log, "Failed to close cursor [reqId=" + reqId + ", cursor=" + cursor + ']', e);
                }

                it.remove();
            }
        }
    }

    /**
     * Checks whether query was cancelled - returns null if true, otherwise increments query descriptor usage count.
     *
     * @param cur Jdbc Cursor.
     * @return False, if query was already cancelled.
     */
    private boolean prepareQueryCancellationMeta(JdbcCursor cur) {
        if (isCancellationSupported()) {
            // Nothing to do - cursor was already removed.
            if (cur == null)
                return false;

            synchronized (reqMux) {
                JdbcQueryDescriptor desc = reqRegister.get(cur.requestId());

                // Query was already cancelled and unregisterd.
                if (desc == null)
                    return false;

                desc.incrementUsageCount();
            }
        }

        return true;
    }

    /**
     * Cleanups cursors or processors and unregistered request if necessary.
     *
     * @param unregisterReq Flag, that detecs whether it's necessary to unregister request.
     * @param reqId Request Id.
     */
    private void cleanupQueryCancellationMeta(boolean unregisterReq, long reqId) {
        if (isCancellationSupported()) {
            boolean clearCursors = false;

            synchronized (reqMux) {
                JdbcQueryDescriptor desc = reqRegister.get(reqId);

                if (desc != null) {
                    // Query was cancelled during execution.
                    if (desc.isCanceled()) {
                        clearCursors = true;

                        unregisterReq = true;
                    }
                    else
                        desc.decrementUsageCount();

                    if (unregisterReq)
                        unregisterRequest(reqId);
                }
            }

            if (clearCursors)
                clearCursors(reqId);
        }
    }
}
