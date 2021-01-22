// ============================================================================
//
// Copyright (C) 2006-2017 Talend Inc. - www.talend.com
//
// This source code is available under agreement available at
// %InstallDIR%\features\org.talend.rcp.branding.%PRODUCTNAME%\%PRODUCTNAME%license.txt
//
// You should have received a copy of the agreement
// along with this program; if not, write to Talend SA
// 9 rue Pages 92150 Suresnes, France
//
// ============================================================================
package org.talend.components.salesforce.runtime;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.avro.Schema;
import org.apache.avro.generic.IndexedRecord;
import org.talend.components.api.component.runtime.Result;
import org.talend.components.api.container.RuntimeContainer;
import org.talend.components.api.exception.ComponentException;
import org.talend.components.api.exception.DataRejectException;
import org.talend.components.salesforce.SalesforceOutputProperties;
import org.talend.components.salesforce.tsalesforcebulkexec.TSalesforceBulkExecProperties;

import com.sforce.async.AsyncApiException;
import com.sforce.ws.ConnectionException;

final class SalesforceBulkExecReader extends SalesforceReader {

    protected SalesforceBulkRuntime bulkRuntime;

    private int batchIndex;

    private List<BulkResult> currentBatchResult;

    private int resultIndex;

    private int successCount;

    private int rejectCount;

    private boolean isOutputUpsertKey;

    public SalesforceBulkExecReader(RuntimeContainer container, SalesforceSource source,
            TSalesforceBulkExecProperties props) {
        super(container, source);
        properties = props;
    }

    @Override
    public boolean start() throws IOException {

        TSalesforceBulkExecProperties sprops = (TSalesforceBulkExecProperties) properties;
        bulkRuntime =
                new SalesforceBulkRuntime(((SalesforceSource) getCurrentSource()).connect(container).bulkConnection);
        bulkRuntime.setConcurrencyMode(sprops.bulkProperties.concurrencyMode.getValue());
        bulkRuntime.setAwaitTime(sprops.bulkProperties.waitTimeCheckBatchState.getValue());
        bulkRuntime.setSafetySwitch(sprops.bulkProperties.safetySwitch.getValue());

        try {
            // We only support CSV file for bulk output
            bulkRuntime
                    .executeBulk(sprops.module.moduleName.getStringValue(), sprops.outputAction.getValue(),
                            sprops.hardDelete.getValue(), sprops.upsertKeyColumn.getStringValue(), "csv",
                            sprops.bulkFilePath.getStringValue(), sprops.bulkProperties.bytesToCommit.getValue(),
                            sprops.bulkProperties.rowsToCommit.getValue());
            if (bulkRuntime.getBatchCount() > 0) {
                batchIndex = 0;
                isOutputUpsertKey =
                        SalesforceOutputProperties.OutputAction.UPSERT.equals(sprops.outputAction.getValue())
                                && sprops.outputUpsertKey.getValue();
                if (isOutputUpsertKey) {
                    currentBatchResult = bulkRuntime.getBatchLog(0, sprops.upsertKeyColumn.getValue());
                } else {
                    currentBatchResult = bulkRuntime.getBatchLog(0);
                }
                resultIndex = 0;
                boolean startable = currentBatchResult.size() > 0;
                if (startable) {
                    countData();
                }
                return startable;
            }
            return false;
        } catch (AsyncApiException | ConnectionException e) {
            throw new IOException(e);
        }
    }

    protected Map<String, String> getResult() {
        return null;
    }

    @Override
    public boolean advance() throws IOException {
        if (++resultIndex >= currentBatchResult.size()) {
            if (++batchIndex >= bulkRuntime.getBatchCount()) {
                return false;
            } else {
                try {
                    if (isOutputUpsertKey) {
                        currentBatchResult = bulkRuntime
                                .getBatchLog(batchIndex,
                                        ((TSalesforceBulkExecProperties) properties).upsertKeyColumn.getValue());
                    } else {
                        currentBatchResult = bulkRuntime.getBatchLog(batchIndex);
                    }
                    resultIndex = 0;
                    boolean isAdvanced = currentBatchResult.size() > 0;
                    if (isAdvanced) {
                        countData();
                    }
                    return isAdvanced;
                } catch (AsyncApiException | ConnectionException e) {
                    throw new IOException(e);
                }
            }
        }
        countData();
        return true;
    }

    @Override
    public IndexedRecord getCurrent() {
        BulkResult result = currentBatchResult.get(resultIndex);
        IndexedRecord record = null;
        try {
            record = ((BulkResultAdapterFactory) getFactory()).convertToAvro(result);
        } catch (IOException e) {
            throw new ComponentException(e);
        }

        if ("true".equalsIgnoreCase((String) result.getValue("Success"))) {
            return record;
        } else {
            Map<String, Object> resultMessage = new HashMap<String, Object>();
            if (isOutputUpsertKey) {
                resultMessage
                        .put("UpsertColumnValue", result
                                .getValue(((TSalesforceBulkExecProperties) properties).upsertKeyColumn.getValue()));
            }
            String error = (String) result.getValue("Error");
            resultMessage.put("error", error);
            resultMessage.put("talend_record", record);
            throw new DataRejectException(resultMessage);
        }

    }

    @Override
    protected Schema getSchema() throws IOException {
        if (querySchema == null) {
            TSalesforceBulkExecProperties sprops = (TSalesforceBulkExecProperties) properties;
            // TODO check the assert : the output schema have values even when no output connector
            querySchema = sprops.schemaFlow.schema.getValue();
        }
        return querySchema;
    }

	@Override
	public void close() throws IOException {
		if (bulkRuntime != null) {
			bulkRuntime.close();
		}
	}

    @Override
    public Map<String, Object> getReturnValues() {
        Result result = new Result();
        result.totalCount = dataCount;
        result.successCount = successCount;
        result.rejectCount = rejectCount;
        return result.toMap();
    }

    protected void countData() {
        dataCount++;
        BulkResult result = currentBatchResult.get(resultIndex);
        if ("true".equalsIgnoreCase(String.valueOf(result.getValue("Success")))) {
            successCount++;
        } else {
            rejectCount++;
        }
    }
}
