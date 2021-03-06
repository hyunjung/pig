/*
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

package org.apache.pig.newplan.logical.relational;

import java.io.IOException;
import java.util.List;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.mapreduce.Job;
import org.apache.pig.LoadFunc;
import org.apache.pig.LoadMetadata;
import org.apache.pig.ResourceSchema;
import org.apache.pig.data.DataType;
import org.apache.pig.impl.PigContext;
import org.apache.pig.impl.io.FileSpec;
import org.apache.pig.impl.logicalLayer.FrontendException;
import org.apache.pig.impl.logicalLayer.schema.Schema;
import org.apache.pig.newplan.Operator;
import org.apache.pig.newplan.PlanVisitor;
import org.apache.pig.newplan.logical.Util;

public class LOLoad extends LogicalRelationalOperator {
    
    private LogicalSchema scriptSchema;
    private FileSpec fs;
    private transient LoadFunc loadFunc;
    transient private Configuration conf;
    private LogicalSchema determinedSchema;
    private List<Integer> requiredFields = null;
    private boolean castInserted = false;
    private LogicalSchema uidOnlySchema;
    private String signature = null;

    /**
     * 
     * @param loader FuncSpec for load function to use for this load.
     * @param schema schema user specified in script, or null if not
     * specified.
     * @param plan logical plan this load is part of.
     */
    public LOLoad(FileSpec loader, LogicalSchema schema, LogicalPlan plan, Configuration conf) {
       super("LOLoad", plan);
       scriptSchema = schema;
       fs = loader;
       this.conf = conf;
    }
    
    public LoadFunc getLoadFunc() throws FrontendException {
        try { 
            if (loadFunc == null && fs!=null) {
                loadFunc = (LoadFunc)PigContext.instantiateFuncFromSpec(fs.getFuncSpec());
                loadFunc.setUDFContextSignature(signature);
            }
            
            return loadFunc;
        }catch (ClassCastException cce) {
            throw new FrontendException(fs.getFuncSpec() + " should implement the LoadFunc interface.", 2236);    		
        }
    }
    
    public void setScriptSchema(LogicalSchema schema) {
        scriptSchema = schema;
    }
    
    public void setRequiredFields(List<Integer> requiredFields) {
        this.requiredFields = requiredFields;
    }
    
    /**
     * Get the schema for this load.  The schema will be either be what was
     * given by the user in the script or what the load functions getSchema
     * call returned.  Otherwise null will be returned, indicating that the
     * schema is unknown.
     * @return schema, or null if unknown
     */
    @Override
    public LogicalSchema getSchema() throws FrontendException {
        if (schema != null)
            return schema;
        
        LogicalSchema originalSchema = null;

        if (determinedSchema==null) {
            determinedSchema = getSchemaFromMetaData();
        }
        
        if (scriptSchema != null && determinedSchema != null) {
            originalSchema = LogicalSchema.merge(scriptSchema, determinedSchema);
        } else if (scriptSchema != null)  originalSchema = scriptSchema;
        else if (determinedSchema != null) originalSchema = determinedSchema;
        
        if (isCastInserted()) {
            for (int i=0;i<originalSchema.size();i++) {
                LogicalSchema.LogicalFieldSchema fs = originalSchema.getField(i);
                if(determinedSchema == null) {
                    // Reset the loads field schema to byte array so that it
                    // will reflect reality.
                    fs.type = DataType.BYTEARRAY;
                } else {
                    // Reset the type to what determinedSchema says it is
                    fs.type = determinedSchema.getField(i).type;
                }
            }
        }
        
        if (originalSchema!=null) {
            uidOnlySchema = originalSchema.mergeUid(uidOnlySchema);
        }
        
        if (requiredFields!=null) {
            schema = new LogicalSchema();
            for (int i=0;i<originalSchema.size();i++) {
                if (requiredFields.contains(i))
                    schema.addField(originalSchema.getField(i));
            }
        } else
            schema = originalSchema;
        
        return schema;
    }

    private LogicalSchema getSchemaFromMetaData() throws FrontendException {
        if (getLoadFunc()!=null && getLoadFunc() instanceof LoadMetadata) {
            try {
                ResourceSchema resourceSchema = ((LoadMetadata)loadFunc).getSchema(getFileSpec().getFileName(), new Job(conf));
                Schema oldSchema = Schema.getPigSchema(resourceSchema);
                return Util.translateSchema(oldSchema);
            } catch (IOException e) {
                throw new FrontendException("Cannot get schema from loadFunc " + loadFunc.getClass().getName(), 2245, e);
            }
        }
        return null;
    }

    public FileSpec getFileSpec() {
        return fs;
    }
    
    @Override
    public void accept(PlanVisitor v) throws FrontendException {
        if (!(v instanceof LogicalRelationalNodesVisitor)) {
            throw new FrontendException("Expected LogicalPlanVisitor", 2223);
        }
        ((LogicalRelationalNodesVisitor)v).visit(this);

    }
    
    public LogicalSchema getDeterminedSchema() {
        return determinedSchema;
    }
    
    @Override
    public boolean isEqual(Operator other) throws FrontendException {
        if (other != null && other instanceof LOLoad) {
            LOLoad ol = (LOLoad)other;
            if (!checkEquality(ol)) return false;
            if (fs == null) {
                if (ol.fs == null) {
                    return true;
                }else{
                    return false;
                }
            }
            
            return fs.equals(ol.fs);
        } else {
            return false;
        }
    }
    
    public void setCastInserted(boolean flag) {
        castInserted = flag;
    }
    
    public boolean isCastInserted() {
        return castInserted;
    }
    
    public Configuration getConfiguration() {
        return conf;
    }
    
    @Override
    public void resetUid() {
        uidOnlySchema = null;
    }
    
    @Override
    public String toString(){
        String str = super.toString();
        return (str + "RequiredFields:" + requiredFields);
    }
    
    public String getSignature() {
        return signature;
    }
    
    public void setAlias(String alias) {
        super.setAlias(alias);
        if (signature==null)
            signature = alias;
    }
    
    /***
     * This method is called by Pig logical planner to setup UDFContext signature.
     * So that loadFunc can use signature to store its own configurations in UDFContext.
     * This is not intend to be called by users
     */
    public void setSignature(String signature) {
        this.signature = signature;
        loadFunc.setUDFContextSignature(signature);
    }
}
