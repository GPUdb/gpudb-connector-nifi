package com.gisfederal.gpudb.processors.GPUdbNiFi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.nifi.annotation.behavior.ReadsAttribute;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.annotation.lifecycle.OnScheduled;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.processor.AbstractProcessor;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.ProcessorInitializationContext;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.util.StandardValidators;

import com.gpudb.BulkInserter;
import com.gpudb.GPUdb;
import com.gpudb.GPUdbBase.Options;
import com.gpudb.GPUdbException;
import com.gpudb.Record;
import com.gpudb.RecordObject;
import com.gpudb.Type;
import com.gpudb.Type.Column;
import com.gpudb.WorkerList;
import com.gpudb.protocol.CreateTableRequest;
import com.gpudb.protocol.HasTableResponse;
import com.gpudb.protocol.InsertRecordsRequest;

@Tags({ "Kinetica", "add", "bulkadd", "put" })
@CapabilityDescription("Bulkloads the contents of FlowFiles to Kinetica in batch intervals (Batch Size setting). Each FlowFile must contain "
        + "attributes that match your Schema definition. "
        + "Example: Given this schema: x|Float|data,y|Float|data,TIMESTAMP|Long|data,TEXT|String|store_only|text_search,AUTHOR|String|text_search|data, "
        + "this processor would expect attributes of x, y, TIMESTAMP, TEXT and AUTHOR in the FlowFile (null or blank values are okay). Case sensitivity "
        + "of the column names matters. "
        + "It is important to set the Batch Size to meet your througput needs. If you are ingesting 10K tuples a second, you will need to set your "
        + "Batch Size to match.")
@ReadsAttribute(attribute = "mime.type", description = "Determines MIME type of input file")
public class PutKinetica extends AbstractProcessor {
    public static final PropertyDescriptor PROP_SERVER = new PropertyDescriptor.Builder().name( KineticaConstants.SERVER_URL )
        .description("URL of the Kinetica server. Example http://172.3.4.19:9191").required(true)
        .addValidator(StandardValidators.URL_VALIDATOR).build();

    public static final PropertyDescriptor PROP_COLLECTION = new PropertyDescriptor.Builder().name( KineticaConstants.COLLECTION_NAME )
        .description("Name of the Kinetica collection").required(false)
        .addValidator(StandardValidators.NON_EMPTY_VALIDATOR).build();

    public static final PropertyDescriptor PROP_TABLE = new PropertyDescriptor.Builder().name( KineticaConstants.TABLE_NAME )
        .description("Name of the Kinetica table").required(true)
        .addValidator(StandardValidators.NON_EMPTY_VALIDATOR).build();

    public static final PropertyDescriptor PROP_SCHEMA = new PropertyDescriptor.Builder().name( KineticaConstants.SCHEMA )
        .description("Schema of the Kinetica table. Schema not required if table exists in Kinetica already."
                     + " Example schema: x|Float|data,y|Float|data,TIMESTAMP|Long|data,TEXT|String|store_only|text_search,AUTHOR|String|text_search|data")
        .required(false).addValidator(StandardValidators.NON_EMPTY_VALIDATOR).build();

    protected static final PropertyDescriptor PROP_BATCH_SIZE = new PropertyDescriptor.Builder().name( KineticaConstants.BATCH_SIZE )
        .description("The maximum number of FlowFiles to process in a single execution. The FlowFiles will be "
                     + "grouped by table, and a batch insert per table will be performed.")
        .required(true).addValidator(StandardValidators.POSITIVE_INTEGER_VALIDATOR).defaultValue("500").build();

    public static final PropertyDescriptor PROP_USERNAME = new PropertyDescriptor.Builder().name( KineticaConstants.USERNAME )
        .description("Username to connect to Kinetica").required(false)
        .addValidator(StandardValidators.NON_EMPTY_VALIDATOR).build();

    public static final PropertyDescriptor PROP_PASSWORD = new PropertyDescriptor.Builder().name( KineticaConstants.PASSWORD )
        .description("Password to connect to Kinetica").required(false)
        .addValidator(StandardValidators.NON_EMPTY_VALIDATOR).sensitive(true).build();

    protected static final PropertyDescriptor UPDATE_ON_EXISTING_PK = new PropertyDescriptor.Builder()
        .name( KineticaConstants.UPDATE_ON_EXISTING_PK )
        .description(
                     "If the table has a primary key, then if the value is 'true' then if any of the records being added have the "
                     + "same primary key as existing records, the existing records are replaced (i.e. *updated*) with the given records. "
                     + "If 'false' and if the records being added have the same primary key as existing records, the given records with "
                     + "existing primary keys are ignored (the existing records are left unchanged). It is quite possible that in this "
                     + "case some of the given records will be inserted and some (those having existing primary keys) will be ignored "
                     + "(or updated). If the specified table does not have a primary key column then this parameter is ignored. ")
        .required(true).addValidator(StandardValidators.BOOLEAN_VALIDATOR).defaultValue("false").build();

    protected static final PropertyDescriptor PROP_REPLICATE_TABLE = new PropertyDescriptor.Builder()
        .name( KineticaConstants.REPLICATE_TABLE )
        .description(
                     "If the Kinetica table doesn't already exist then it will created by this processor. A value of true indicates that"
                     + " the table that is created should be replicated.")
        .required(true).addValidator(StandardValidators.BOOLEAN_VALIDATOR).defaultValue("false").build();

    public static final PropertyDescriptor PROP_DATE_FORMAT = new PropertyDescriptor.Builder()
        .name( KineticaConstants.DATE_FORMAT )
        .description("Provide the date format used for your datetime values"
                     + " Example: yyyy/MM/dd HH:mm:ss")
        .required(false).addValidator(StandardValidators.NON_EMPTY_VALIDATOR).build();

    public static final PropertyDescriptor PROP_TIMEZONE = new PropertyDescriptor.Builder()
        .name( KineticaConstants.TIMEZONE )
        .description(
                     "Provide the timezone the data was created in. If no timezone is set, the current timezone will be used."
                     + " Example: EST")
        .required(false).addValidator(StandardValidators.NON_EMPTY_VALIDATOR).build();

    public static final Relationship REL_SUCCESS = new Relationship.Builder().name( KineticaConstants.SUCCESS )
        .description("All FlowFiles that are written to Kinetica are routed to this relationship").build();

    public static final Relationship REL_FAILURE = new Relationship.Builder().name( KineticaConstants.FAILURE )
        .description("All FlowFiles that cannot be written to Kinetica are routed to this relationship").build();

    private GPUdb gpudb;
    private String tableName;
    public Type objectType;
    private List<PropertyDescriptor> descriptors;
    private Set<Relationship> relationships;
    private boolean updateOnExistingPk;
    private String dataFormat;
    private String timeZone;
    private static final String PROCESSOR_NAME = "PutKinetica";

    @Override
    protected void init(final ProcessorInitializationContext context) {
        final List<PropertyDescriptor> descriptorList = new ArrayList<>();
        descriptorList.add(PROP_SERVER);
        descriptorList.add(PROP_COLLECTION);
        descriptorList.add(PROP_TABLE);
        descriptorList.add(PROP_SCHEMA);
        descriptorList.add(PROP_BATCH_SIZE);
        descriptorList.add(PROP_USERNAME);
        descriptorList.add(PROP_PASSWORD);
        descriptorList.add(UPDATE_ON_EXISTING_PK);
        descriptorList.add(PROP_REPLICATE_TABLE);
        descriptorList.add(PROP_DATE_FORMAT);
        descriptorList.add(PROP_TIMEZONE);

        this.descriptors = Collections.unmodifiableList(descriptorList);

        final Set<Relationship> relationshipList = new HashSet<>();
        relationshipList.add(REL_SUCCESS);
        relationshipList.add(REL_FAILURE);
        this.relationships = Collections.unmodifiableSet(relationshipList);
    }

    @Override
    public Set<Relationship> getRelationships() {
        return relationships;
    }

    @Override
    public final List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return descriptors;
    }

    private Type createTable(ProcessContext context, String schemaStr) throws GPUdbException {
        getLogger().debug(PROCESSOR_NAME + " createTable:" + tableName + ", schemaStr:" + schemaStr);
        HasTableResponse response = gpudb.hasTable(tableName, null);
        if (response.getTableExists()) {
            return (null);
        }
        List<Column> attributes = new ArrayList<>();
        int maxPrimaryKey = -1;
        String[] fieldArray = schemaStr.split(",");
        for (String fieldStr : fieldArray) {
            String[] split = fieldStr.split("\\|", -1);
            String name = split[0];
            Class<?> type;
            getLogger().debug(PROCESSOR_NAME + " field name:" + name + ", type:" + split[1].toLowerCase());
            if (split.length > 1) {
                switch (split[1].toLowerCase()) {
                case "double":
                    type = Double.class;
                    break;

                case "float":
                    type = Float.class;
                    break;

                case "integer":
                case "int":
                    type = Integer.class;
                    break;

                case "long":
                    type = Long.class;
                    break;

                case "string":
                    type = String.class;
                    break;

                default:
                    throw new GPUdbException("Invalid data type \"" + split[1] + "\" for attribute " + name + ".");
                }
            } else {
                type = String.class;
            }

            int primaryKey;
            List<String> annotations = new ArrayList<>();

            for (int j = 2; j < split.length; j++) {
                String annotation = split[j].toLowerCase().trim();

                if (annotation.startsWith("$primary_key")) {
                    int openIndex = annotation.indexOf('(');
                    int closeIndex = annotation.indexOf(')', openIndex);
                    int keyIndex = -1;

                    if (openIndex != -1 && closeIndex != -1) {
                        try {
                            keyIndex = Integer.parseInt(annotation.substring(openIndex + 1, closeIndex));
                        } catch (NumberFormatException ex) {
                        }
                    }

                    if (keyIndex != -1) {
                        primaryKey = keyIndex;
                        maxPrimaryKey = Math.max(primaryKey, maxPrimaryKey);
                    } else {
                        primaryKey = ++maxPrimaryKey;
                    }
                } else {
                    annotations.add(annotation);
                }
            }

            attributes.add(new Column(name, type, annotations));
        }
        getLogger().debug(PROCESSOR_NAME + " created type:" + attributes);
        Type type = new Type("", attributes);

        String typeId = type.create(gpudb);
        response = gpudb.hasTable(tableName, null);
        Map<String, String> create_table_options;
        String parent = context.getProperty(PROP_COLLECTION).getValue();
        if (parent == null) {
            parent = "";
        }

        if (!response.getTableExists()) {
            boolean replicated_flag = context.getProperty(PROP_REPLICATE_TABLE).isSet()
                    && context.getProperty(PROP_REPLICATE_TABLE).asBoolean().booleanValue();
            getLogger().debug(PROCESSOR_NAME + " replicated_flag = " + replicated_flag);

            create_table_options = GPUdb.options(CreateTableRequest.Options.COLLECTION_NAME, parent,
                    CreateTableRequest.Options.IS_REPLICATED,
                    replicated_flag ? CreateTableRequest.Options.TRUE : CreateTableRequest.Options.FALSE);

            getLogger().debug(PROCESSOR_NAME + " create_table_options has " + create_table_options.size() + "properties");
            gpudb.createTable(context.getProperty(PROP_TABLE).getValue(), typeId, create_table_options);
        }

        gpudb.addKnownType(typeId, RecordObject.class);
        return type;
    }

    @OnScheduled
    public void onScheduled(final ProcessContext context) throws GPUdbException {
        Options option = new Options();
        if (context.getProperty(PROP_USERNAME).getValue() != null
                && context.getProperty(PROP_PASSWORD).getValue() != null) {
            option.setUsername(context.getProperty(PROP_USERNAME).getValue());
            option.setPassword(context.getProperty(PROP_PASSWORD).getValue());
        }
        gpudb = new GPUdb(context.getProperty(PROP_SERVER).getValue(), option);
        tableName = context.getProperty(PROP_TABLE).getValue();
        updateOnExistingPk = context.getProperty(UPDATE_ON_EXISTING_PK).asBoolean().booleanValue();
        dataFormat = context.getProperty(PROP_DATE_FORMAT).getValue();
        timeZone = context.getProperty(PROP_TIMEZONE).getValue();

        HasTableResponse response;

        try {
            response = gpudb.hasTable(tableName, null);
        } catch (GPUdbException ex) {
            getLogger().error(PROCESSOR_NAME + " Error: Failed hasTable, exception:" + ex.getMessage());
            response = null;
        }

        if ((response != null) && (response.getTableExists())) {
            getLogger().debug(PROCESSOR_NAME + " getting type from table:" + tableName);
            objectType = Type.fromTable(gpudb, tableName);
            getLogger().debug(PROCESSOR_NAME + " objectType:" + objectType.toString());
        } else if (context.getProperty(PROP_SCHEMA).isSet()) {
            objectType = createTable(context, context.getProperty(PROP_SCHEMA).getValue());
        } else {
            objectType = null;
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public void onTrigger(final ProcessContext context, final ProcessSession session) throws ProcessException {
        final List<FlowFile> successes = new ArrayList<>();
        final int batchSize = context.getProperty(PROP_BATCH_SIZE).asInteger();
        BulkInserter<Record> bulkInserter = null;
        WorkerList workers = null;

        // get flowfiles and continue to ad them to the BulkInserter. It will
        // use the batch size to flush them to Kinetica automatically
        List<FlowFile> flowFiles = session.get(batchSize);
        if (flowFiles == null || flowFiles.size() == 0) {
            return;
        } else {
            getLogger().debug(PROCESSOR_NAME + " Found {} rows for insert.", new Object[] { flowFiles.size(), null, null });
        }

        try {
            // bulk inserter automatically flushes to Kinetica when the batch
            // size is met
            workers = new WorkerList(gpudb);
            bulkInserter = new BulkInserter<Record>(gpudb, tableName, objectType, batchSize, GPUdb.options(
                    InsertRecordsRequest.Options.UPDATE_ON_EXISTING_PK,
                    updateOnExistingPk ? InsertRecordsRequest.Options.TRUE : InsertRecordsRequest.Options.FALSE),
                    workers);

        } catch (Exception e) {
            getLogger().error( PROCESSOR_NAME + 
                    " Error: Found failed to create a BulkInserter, please check error logs for more details.",
                    new Object[] { null, null, null });
            return;
        }

        final long start = System.currentTimeMillis();
        for (final FlowFile flowFile : flowFiles) {
            Record object = createGPUdbRecord(flowFile, session);
            if (object != null) {
                try {
                    bulkInserter.insert(object);
                    successes.add(flowFile);
                } catch (BulkInserter.InsertException e) {
                    // Get any records that failed to insert and retry them
                    getLogger().error(PROCESSOR_NAME + " Error: " + e.getMessage() );
                    session.transfer(flowFile, REL_FAILURE);
                }
            } else {
                // Failed to create a Record Object, mark record as failed
                getLogger().error( PROCESSOR_NAME + 
                        " Error: Found failed to create a Record Object, please check error logs for more details.",
                        new Object[] { null, null, null });
                session.transfer(flowFile, REL_FAILURE);
            }
        }

        // Flush the bulk inserter object to make sure all objects are inserted
        try {
            bulkInserter.flush();
        } catch (BulkInserter.InsertException e) {
            getLogger().error(PROCESSOR_NAME + " Error: " + e.getMessage());
        }

        final long sendMillis = System.currentTimeMillis() - start;
        // mark all flowfiles as successful if they made it to Kinetica
        for (FlowFile insertedFlowFile : successes) {
            session.transfer(insertedFlowFile, REL_SUCCESS);
            final String details = "Insert " + insertedFlowFile.toString() + " into Kinetica";
            session.getProvenanceReporter().send(insertedFlowFile, PROP_SERVER + " " + PROP_TABLE, details, sendMillis);
        }
    }

    /*
     * Create a Record for the Flowfile The Kinetica Record object will be used
     * to map to the attributes in the FlowFile Attributes that don't exist in
     * the Kinetica Record object will be ignored The Kinetica Record was
     * created from the pipe delimited schema
     */
    @SuppressWarnings("rawtypes")
    private Record createGPUdbRecord(FlowFile flowFile, ProcessSession session) {
        Record object = objectType.newInstance();
        String value = null;
        String columnName = null;

        Map attributeMap = flowFile.getAttributes();
        for (Column column : objectType.getColumns()) {
            try {
                columnName = column.getName();
                if (attributeMap.containsKey(columnName)) {
                    value = attributeMap.get(columnName).toString();
                } else {
                    value = null;
                }

                boolean timeStamp = KineticaUtilities.checkForTimeStamp( column );

                if (timeStamp && value != null) {
                    if (StringUtils.isNumeric(value)) {
                        long valueLong;
                        try {
                            valueLong = Long.parseLong(value);
                        } catch (NumberFormatException ex) {
                            valueLong = 0;
                        }

                        object.put(columnName, valueLong);
                    } else {
                        Long timestamp = KineticaUtilities.parseDate(value, dataFormat, timeZone, getLogger());
                        object.put(columnName, timestamp);
                    }
                } else if (column.getType() == Double.class && value != null) {
                    double valueDouble;
                    try {
                        valueDouble = Double.parseDouble(value);
                    } catch (NumberFormatException ex) {
                        valueDouble = 0;
                    }
                    object.put(columnName, valueDouble);
                } else if (column.getType() == Float.class && value != null) {
                    float valueFloat;
                    try {
                        valueFloat = Float.parseFloat(value);
                    } catch (NumberFormatException ex) {
                        valueFloat = 0;
                    }
                    object.put(columnName, valueFloat);
                } else if (column.getType() == Integer.class && value != null) {
                    int valueInt;
                    try {
                        valueInt = Integer.parseInt(value);
                    } catch (NumberFormatException ex) {
                        valueInt = 0;
                    }
                    object.put(columnName, valueInt);
                } else if (column.getType() == java.lang.Long.class && value != null) {
                    long valueLong;
                    try {
                        valueLong = Long.parseLong(value);
                    } catch (NumberFormatException ex) {
                        valueLong = 0;
                    }

                    object.put(columnName, valueLong);
                } else {
                    if (value != null && !value.trim().equals("")) {
                        object.put(columnName, value);
                    }
                }

                getLogger().debug(PROCESSOR_NAME + " Found {} column with value {} inserting into Kinetica.",
                        new Object[] { columnName, value, null });
            } catch (Exception e) {
                // if the flow file fails to become an object, mark it as failed
                // and null out the object for return handling
                session.transfer(flowFile, REL_FAILURE);
                getLogger().error(PROCESSOR_NAME +  " Error: Found {} column with value {} and failed to create a Record Obect.",
                        new Object[] { columnName, value, null });
                object = null;
            }
        }

        return object;
    }
}
