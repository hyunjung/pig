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
package org.apache.pig.data;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.pig.PigCounters;
import org.apache.pig.backend.hadoop.executionengine.mapReduceLayer.PigMapReduce;


public class InternalCachedBag extends DefaultAbstractBag {
    private static final long serialVersionUID = 1L;

    private static final Log log = LogFactory.getLog(InternalCachedBag.class);
    private transient int cacheLimit;
    private transient long maxMemUsage;
    private transient long memUsage;
    private transient DataOutputStream out;
    private transient boolean addDone;
    private transient TupleFactory factory;

    // used to store number of tuples spilled until counter is incremented
    private transient int numTuplesSpilled = 0; 
 
    public InternalCachedBag() {
        this(1);
    }

    public InternalCachedBag(int bagCount) {       
        float percent = 0.2F;
        
    	if (PigMapReduce.sJobConfInternal.get() != null) {
    		String usage = PigMapReduce.sJobConfInternal.get().get("pig.cachedbag.memusage");
    		if (usage != null) {
    			percent = Float.parseFloat(usage);
    		}
    	}

        init(bagCount, percent);
    }  
    
    public InternalCachedBag(int bagCount, float percent) {
    	init(bagCount, percent);
    }
    
    private void init(int bagCount, float percent) {
    	factory = TupleFactory.getInstance();        
    	mContents = new ArrayList<Tuple>();             
             	 
    	long max = Runtime.getRuntime().maxMemory();
        maxMemUsage = (long)(((float)max * percent) / (float)bagCount);
        cacheLimit = Integer.MAX_VALUE;
        
        // set limit to 0, if memusage is 0 or really really small.
        // then all tuples are put into disk
        if (maxMemUsage < 1) {
        	cacheLimit = 0;
        }
        
        addDone = false;
    }

    public void add(Tuple t) {
    	
        if(addDone) {
            throw new IllegalStateException("InternalCachedBag is closed for adding new tuples");
        }
                
        if(mContents.size() < cacheLimit)  {
            mContents.add(t);           
            if(mContents.size() < 100)
            {
                memUsage += t.getMemorySize();
                long avgUsage = memUsage / (long)mContents.size();
                if (avgUsage > 0) {
                	cacheLimit = (int)(maxMemUsage / avgUsage);
                }
            }
        } else {
            // above cacheLimit, spill to disk
            try {
                if(out == null) {
                	if (log.isDebugEnabled()) {
                		log.debug("Memory can hold "+ mContents.size() + " records, put the rest in spill file.");
                	}
                    out = getSpillFile();
                    incSpillCount(PigCounters.PROACTIVE_SPILL_COUNT_BAGS);
                }
                t.write(out);
                
                //periodically update number of tuples spilled 
                numTuplesSpilled++;
                if(numTuplesSpilled > 1000){
                    updateSpillRecCounter();
                }
            }
            catch(IOException e) {
                throw new RuntimeException(e);
            }
        }
        
        mSize++;
    }

    private void updateSpillRecCounter() {
        incSpillCount(PigCounters.PROACTIVE_SPILL_COUNT_RECS, numTuplesSpilled);
        numTuplesSpilled = 0;
    }

    public void addAll(DataBag b) {
    	Iterator<Tuple> iter = b.iterator();
    	while(iter.hasNext()) {
    		add(iter.next());
    	}
    }

    public void addAll(Collection<Tuple> c) {
    	Iterator<Tuple> iter = c.iterator();
    	while(iter.hasNext()) {
    		add(iter.next());
    	}
    }
    
    private void addDone() {
        if(out != null) {
            try {
                out.flush();
                out.close();
            }
            catch(IOException e) { 
            	// ignore
            }
        }
        if(numTuplesSpilled > 0)
            updateSpillRecCounter();
        addDone = true;
    }

    public void clear() {
    	if (!addDone) {
    	    addDone();
    	}
        super.clear();
        addDone = false;
        out = null;
    }
    
    public boolean isDistinct() {
        return false;
    }

    public boolean isSorted() {
        return false;
    }

    public Iterator<Tuple> iterator() {
    	if(!addDone) {
    		// close the spill file and mark adding is done
    		// so further adding is disallowed.
    		addDone();
        }
    	return new CachedBagIterator();
    }

    public long spill()
    {
        throw new RuntimeException("InternalCachedBag.spill() should not be called");
    }
    
    private class CachedBagIterator implements Iterator<Tuple> {
        Iterator<Tuple> iter;
        DataInputStream in;
        Tuple next;
        
        long numTuplesRead = 0;
        
        public CachedBagIterator() {
            iter = mContents.iterator();
            if(mSpillFiles != null && mSpillFiles.size() > 0) {
                File file = mSpillFiles.get(0);
                try {
                    in = new DataInputStream(new BufferedInputStream(new FileInputStream(file)));
                }
                catch(FileNotFoundException fnfe) {
                    String msg = "Unable to find our spill file.";
                    throw new RuntimeException(msg, fnfe);
                }
            }
        }



        public boolean hasNext() {
            if (next != null) {
                return true;        		
            }

            if(iter.hasNext()){
                next = iter.next();
                return true;
            }
            
            if(in == null) {
                return false;
            }
            
            try {
            	Tuple t = factory.newTuple();
            	t.readFields(in);
            	next = t;
            	return true;
            }catch(EOFException eof) {
            	try{
            		in.close();
            	}catch(IOException e) {
            		
            	}            
            	in = null;
            	return false;
            }catch(IOException e) {            	 
                String msg = "Unable to read our spill file.";
                throw new RuntimeException(msg, e);               
            }
        }

        public Tuple next() {  
            if (next == null) {
                if (!hasNext()) {
                    throw new NoSuchElementException("No more elements from iterator");
                }
            }
            Tuple t = next;
            next = null;

            numTuplesRead++;
            // This will report progress every 16383 records.
            if ((numTuplesRead & 0x3fff) == 0) reportProgress();
            
            return t;
        }

        public void remove() {
        	throw new UnsupportedOperationException("remove is not supported for CachedBagIterator");
        }

    }

}

