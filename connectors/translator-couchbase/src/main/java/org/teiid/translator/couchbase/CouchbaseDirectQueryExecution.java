/*
 * JBoss, Home of Professional Open Source.
 * See the COPYRIGHT.txt file distributed with this work for information
 * regarding copyright ownership.  Some portions may be licensed
 * to Red Hat, Inc. under one or more contributor license agreements.
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
package org.teiid.translator.couchbase;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import javax.resource.ResourceException;

import org.teiid.core.types.BlobImpl;
import org.teiid.core.types.BlobType;
import org.teiid.core.types.InputStreamFactory;
import org.teiid.couchbase.CouchbaseConnection;
import org.teiid.language.Argument;
import org.teiid.language.Command;
import org.teiid.logging.LogConstants;
import org.teiid.logging.LogManager;
import org.teiid.metadata.RuntimeMetadata;
import org.teiid.translator.DataNotAvailableException;
import org.teiid.translator.ExecutionContext;
import org.teiid.translator.ProcedureExecution;
import org.teiid.translator.TranslatorException;

import com.couchbase.client.java.query.N1qlQueryRow;

public class CouchbaseDirectQueryExecution extends CouchbaseExecution implements ProcedureExecution {
    
    private List<Argument> arguments;
    
    private Iterator<N1qlQueryRow> results;

    public CouchbaseDirectQueryExecution(List<Argument> arguments, Command command, CouchbaseExecutionFactory executionFactory, ExecutionContext executionContext, RuntimeMetadata metadata, CouchbaseConnection connection) {
        super(executionFactory, executionContext, metadata, connection);
        this.arguments = arguments;
    }
    
    @Override
    public void execute() throws TranslatorException {
        String n1ql = (String)this.arguments.get(0).getArgumentValue().getValue();
        LogManager.logDetail(LogConstants.CTX_CONNECTOR, CouchbasePlugin.Util.gs(CouchbasePlugin.Event.TEIID29001, n1ql));
        executionContext.logCommand(n1ql);
        try {
            this.results = connection.execute(n1ql).iterator();
        } catch (ResourceException e) {
            throw new TranslatorException(e);
        }
    }
    
    @Override
    public List<?> next() throws TranslatorException, DataNotAvailableException {
        ArrayList<Object[]> returns = new ArrayList<Object[]>(1);
        ArrayList<Object> result = new ArrayList<Object>(1);
        if(this.results != null && this.results.hasNext()) {
            final N1qlQueryRow row = this.results.next();
            InputStreamFactory isf = new InputStreamFactory() {
                @Override
                public InputStream getInputStream() throws IOException {
                    return new ByteArrayInputStream(row.byteValue());
                }
            };
            result.add(new BlobType(new BlobImpl(isf)));
            returns.add(result.toArray());
            return returns;
        } else {
            return null;
        }
        
    }

    @Override
    public void close() {
        results = null;
    }

    @Override
    public void cancel() throws TranslatorException {
        close();
    }

    @Override
    public List<?> getOutputParameterValues() throws TranslatorException {
        return null;
    }

}