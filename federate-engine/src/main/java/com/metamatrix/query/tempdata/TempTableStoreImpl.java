/*
 * JBoss, Home of Professional Open Source.
 * Copyright (C) 2008 Red Hat, Inc.
 * Copyright (C) 2000-2007 MetaMatrix, Inc.
 * Licensed to Red Hat, Inc. under one or more contributor 
 * license agreements.  See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA
 * 02110-1301 USA.
 */

package com.metamatrix.query.tempdata;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.metamatrix.api.exception.MetaMatrixComponentException;
import com.metamatrix.api.exception.query.ExpressionEvaluationException;
import com.metamatrix.api.exception.query.QueryMetadataException;
import com.metamatrix.common.buffer.BlockedException;
import com.metamatrix.common.buffer.BufferManager;
import com.metamatrix.common.buffer.TupleBatch;
import com.metamatrix.common.buffer.TupleSource;
import com.metamatrix.common.buffer.TupleSourceID;
import com.metamatrix.common.buffer.TupleSourceNotFoundException;
import com.metamatrix.common.buffer.BufferManager.TupleSourceStatus;
import com.metamatrix.common.buffer.BufferManager.TupleSourceType;
import com.metamatrix.core.util.Assertion;
import com.metamatrix.query.eval.Evaluator;
import com.metamatrix.query.execution.QueryExecPlugin;
import com.metamatrix.query.metadata.TempMetadataAdapter;
import com.metamatrix.query.metadata.TempMetadataStore;
import com.metamatrix.query.processor.proc.UpdateCountTupleSource;
import com.metamatrix.query.resolver.util.ResolverUtil;
import com.metamatrix.query.sql.lang.BulkInsert;
import com.metamatrix.query.sql.lang.Command;
import com.metamatrix.query.sql.lang.Create;
import com.metamatrix.query.sql.lang.Drop;
import com.metamatrix.query.sql.lang.Insert;
import com.metamatrix.query.sql.lang.Query;
import com.metamatrix.query.sql.symbol.ElementSymbol;
import com.metamatrix.query.sql.symbol.Expression;
import com.metamatrix.query.sql.symbol.GroupSymbol;
import com.metamatrix.query.util.TypeRetrievalUtil;

/** 
 * @since 5.5
 */
public class TempTableStoreImpl implements TempTableStore{
    private BufferManager buffer;
    private TempMetadataStore tempMetadataStore = new TempMetadataStore();
    private Map groupToTupleSourceID = new HashMap();
    private String sessionID;
    private TempTableStore parentTempTableStore;
    
    public TempTableStoreImpl(BufferManager buffer, String sessionID, TempTableStore parentTempTableStore) {
        this.buffer = buffer;
        this.sessionID = sessionID;
        this.parentTempTableStore = parentTempTableStore;
    }
    
    public void addTempTable(Command command, boolean removeExistingTable) throws MetaMatrixComponentException{
        String tempTableName = null;
        List columns = null;

        switch (command.getType()) {
            case Command.TYPE_CREATE:
                tempTableName = ((Create)command).getTable().getName().toUpperCase();
                columns = ((Create)command).getColumns();
                break;
            case Command.TYPE_QUERY:
                Query query = (Query)command;
                if(query.getInto() != null) {
                    tempTableName = query.getInto().getGroup().getName().toUpperCase();
                    columns = query.getSelect().getSymbols();
                    break;
                }else if(((GroupSymbol)query.getFrom().getGroups().get(0)).isTempGroupSymbol()) {
                    tempTableName = ((GroupSymbol)query.getFrom().getGroups().get(0)).getNonCorrelationName().toUpperCase();
                    columns = query.getSelect().getSymbols();
                    break;
                }
                return;
            case Command.TYPE_INSERT:
                Insert insert = (Insert)command;
                GroupSymbol group = insert.getGroup();
                if(group.isTempGroupSymbol()) {
                    tempTableName = group.getNonCorrelationName().toUpperCase();
                    columns = insert.getVariables();
                    break; 
                }
                return;
            default:
                return;
        }
        
        if(tempMetadataStore.getTempGroupID(tempTableName) != null) {
            if(removeExistingTable) {
                removeTempTableByName(tempTableName);
            }else {
                throw new MetaMatrixComponentException(QueryExecPlugin.Util.getString("TempTableStore.table_exist_error", tempTableName));//$NON-NLS-1$
            }
        }
        
        //add metadata
        tempMetadataStore.addTempGroup(tempTableName, columns, false, true);
        //create tuple source
        TupleSourceID tsId = buffer.createTupleSource(columns, TypeRetrievalUtil.getTypeNames(columns), sessionID, TupleSourceType.PROCESSOR);
        try {
            buffer.setStatus(tsId, TupleSourceStatus.FULL);
        }catch(TupleSourceNotFoundException e) {
            //should never happen because the TupleSourceID is just created
            Assertion.failed("Could not find local tuple source for inserting into temp table."); //$NON-NLS-1$
        }
        groupToTupleSourceID.put(tempTableName, tsId);
    }
    
    public void removeTempTable(Command command) throws MetaMatrixComponentException{
        if(command.getType() == Command.TYPE_DROP) {
            String tempTableName = ((Drop)command).getTable().getName().toUpperCase();
            removeTempTableByName(tempTableName);
        }
    }
    
    public void removeTempTableByName(String tempTableName) throws MetaMatrixComponentException {
        tempMetadataStore.removeTempGroup(tempTableName);
        TupleSourceID tsId = (TupleSourceID)this.groupToTupleSourceID.remove(tempTableName);
        if(tsId != null) {
            try {
                buffer.removeTupleSource(tsId);
            }catch(TupleSourceNotFoundException e) {}
        }      
    }
    
    public TempMetadataStore getMetadataStore() {
        return tempMetadataStore;
    }
        
    public TupleSource registerRequest(Command command) throws MetaMatrixComponentException, ExpressionEvaluationException{
        if(!hasTempTable(command)) {
            return null;
        }
        
        switch (command.getType()) {
            case Command.TYPE_INSERT:
            {
                return addTuple((Insert)command);
            }
            case Command.TYPE_QUERY:
            {
                Query query = (Query)command;
                GroupSymbol group = (GroupSymbol)query.getFrom().getGroups().get(0);
                return connectTupleSource(group.getNonCorrelationName(), command);
            }
            case Command.TYPE_CREATE:
            {
                addTempTable(command, false);
                return new UpdateCountTupleSource(0);
            }
            case Command.TYPE_DROP:
            {
                removeTempTable(command);
                return new UpdateCountTupleSource(0);
            }
        }
        throw new AssertionError("unhandled temp table reqest");
    }
    
    public void removeTempTables() throws MetaMatrixComponentException{
        List names = new ArrayList( groupToTupleSourceID.keySet() );
        Iterator iter = names.iterator();

        while(iter.hasNext()) {
            removeTempTableByName((String)iter.next());
        }
    }
    
    private TupleSource connectTupleSource(String tableName, Command command) throws MetaMatrixComponentException{
        TupleSourceID tsId = getTupleSourceID(tableName, command);
        try {
            return buffer.getTupleSource(tsId);
        }catch(TupleSourceNotFoundException e) {
            throw new MetaMatrixComponentException(e);
        }
    }
    
    private TupleSourceID getTupleSourceID(String tempTableID, Command command) throws MetaMatrixComponentException{
       
        TupleSourceID tsID = (TupleSourceID)groupToTupleSourceID.get(tempTableID.toUpperCase());
        if(tsID != null) {
            return tsID;
        }
        if(this.parentTempTableStore != null){
    		tsID = this.parentTempTableStore.getTupleSourceID(tempTableID);
    	    if(tsID != null) {
    	        return tsID;
    	    }
        }
        addTempTable(command, true);       
        return (TupleSourceID)groupToTupleSourceID.get(tempTableID.toUpperCase());
    }
    
    
    private TupleSource addTuple(Insert insert) throws MetaMatrixComponentException, ExpressionEvaluationException {
        GroupSymbol group = insert.getGroup();
        TupleSourceID tsId = getTupleSourceID(group.getNonCorrelationName(), insert);
        int tuplesAdded = 0;
        try {
            int rowCount = buffer.getRowCount(tsId);
            TupleBatch tupleBatch;
            List elements = ResolverUtil.resolveElementsInGroup(group, new TempMetadataAdapter(null, tempMetadataStore));
            
            if ( insert instanceof BulkInsert ) {
                List tuples = ((BulkInsert)insert).getRows();
                
                tuplesAdded = tuples.size();

                // Buffer manager has 1 based index for the tuple sources
                tupleBatch = new TupleBatch((++rowCount), tuples);
                
            } else {
                
                tuplesAdded = 1;
                List tuple = new ArrayList(elements.size());
                for(Iterator i = elements.iterator(); i.hasNext();){
                    ElementSymbol symbol = (ElementSymbol)i.next();
                    int index = insert.getVariables().indexOf(symbol);
                    Object value = null;
                    if (index != -1) {
                    	Expression expr = (Expression)insert.getValues().get(index);
                        value = Evaluator.evaluate(expr);
                    }
                    tuple.add(value);
                }
                tupleBatch = new TupleBatch(++rowCount, new List[]{tuple});
            }                        
            
            buffer.addTupleBatch(tsId, tupleBatch);
        } catch (TupleSourceNotFoundException err) {
            throw new MetaMatrixComponentException(err);
        } catch (BlockedException err) {
            throw new MetaMatrixComponentException(err);
        } catch (QueryMetadataException err) {
            throw new MetaMatrixComponentException(err);
        }        
        
        return new UpdateCountTupleSource(tuplesAdded);
    }
    
    public boolean hasTempTable(Command command){
        switch (command.getType()) {
            case Command.TYPE_INSERT:
            {
                Insert insert = (Insert)command;
                GroupSymbol group = insert.getGroup();
                return group.isTempGroupSymbol();
            }
            case Command.TYPE_QUERY:
            {
                if(command instanceof Query) {
                    Query query = (Query)command;
                    GroupSymbol group = (GroupSymbol)query.getFrom().getGroups().get(0);
                    return group.isTempGroupSymbol(); 
                }
                break;
            }
            case Command.TYPE_CREATE:
                return true;
            case Command.TYPE_DROP:
                return true;
        }
        return false;
    }
    
    public Set getAllTempTables() {
        return new HashSet(this.groupToTupleSourceID.keySet());
    }
    
    public TupleSourceID getTupleSourceID(String tempTableName) {
    	return (TupleSourceID)groupToTupleSourceID.get(tempTableName.toUpperCase());
    } 
}
