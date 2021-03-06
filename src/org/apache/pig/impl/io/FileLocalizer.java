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
package org.apache.pig.impl.io;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.Stack;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.pig.ExecType;
import org.apache.pig.PigException;
import org.apache.pig.backend.datastorage.ContainerDescriptor;
import org.apache.pig.backend.datastorage.DataStorage;
import org.apache.pig.backend.datastorage.DataStorageException;
import org.apache.pig.backend.datastorage.ElementDescriptor;
import org.apache.pig.backend.datastorage.SeekableInputStream;
import org.apache.pig.backend.datastorage.SeekableInputStream.FLAGS;
import org.apache.pig.backend.executionengine.ExecException;
import org.apache.pig.backend.hadoop.datastorage.ConfigurationUtil;
import org.apache.pig.backend.hadoop.datastorage.HDataStorage;
import org.apache.pig.backend.hadoop.datastorage.HPath;
import org.apache.pig.backend.hadoop.executionengine.mapReduceLayer.PigMapReduce;
import org.apache.pig.impl.PigContext;

public class FileLocalizer {
    private static final Log log = LogFactory.getLog(FileLocalizer.class);
    
    static public final String LOCAL_PREFIX  = "file:";
    static public final int STYLE_UNIX = 0;
    static public final int STYLE_WINDOWS = 1;

    public static class DataStorageInputStreamIterator extends InputStream {
        InputStream current;
        ElementDescriptor[] elements;
        int currentElement;
        
        public DataStorageInputStreamIterator(ElementDescriptor[] elements) {
            this.elements = elements;
        }

        private boolean isEOF() throws IOException {
            if (current == null) {
                if (currentElement == elements.length) {
                    return true;
                }
                current = elements[ currentElement++ ].open();
            }
            return false;
        }

        private void doNext() throws IOException {
            current.close();
            current = null;
        }

        @Override
        public int read() throws IOException {
            while (!isEOF()) {
                int rc = current.read();
                if (rc != -1)
                    return rc;
                doNext();
            }
            return -1;
        }

        @Override
        public int available() throws IOException {
            if (isEOF())
                return 0;
            return current.available();
        }

        @Override
        public void close() throws IOException {
            if (current != null) {
                current.close();
                current = null;
            }
            currentElement = elements.length;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int count = 0;
            while (!isEOF() && len > 0) {
                int rc = current.read(b, off, len);
                if (rc <= 0) {
                    doNext();
                    continue;
                }
                off += rc;
                len -= rc;
                count += rc;
            }
            return count == 0 ? (isEOF() ? -1 : 0) : count;
        }

        @Override
        public int read(byte[] b) throws IOException {
            return read(b, 0, b.length);
        }

        @Override
        public long skip(long n) throws IOException {
            while (!isEOF() && n > 0) {
                n -= current.skip(n);
            }
            return n;
        }

    }

    static String checkDefaultPrefix(ExecType execType, String fileSpec) {
        if (fileSpec.startsWith(LOCAL_PREFIX))
            return fileSpec;
        return (execType == ExecType.LOCAL ? LOCAL_PREFIX : "") + fileSpec;
    }

    /**
     * This function is meant to be used if the mappers/reducers want to access any HDFS file
     * @param fileName
     * @return InputStream of the open file.
     * @throws IOException
     */
    public static InputStream openDFSFile(String fileName) throws IOException {
        Configuration conf = PigMapReduce.sJobConfInternal.get();
        if (conf == null) {
            throw new RuntimeException(
                    "can't open DFS file while executing locally");
        }
        
        return openDFSFile(fileName, ConfigurationUtil.toProperties(conf));

    }

    public static InputStream openDFSFile(String fileName, Properties properties) throws IOException{
        DataStorage dds = new HDataStorage(properties);
        ElementDescriptor elem = dds.asElement(fileName);
        return openDFSFile(elem);
    }
    
    public static long getSize(String fileName) throws IOException {
    	Configuration conf = PigMapReduce.sJobConfInternal.get();

    	if (conf == null) {
    		throw new RuntimeException(
                "can't open DFS file while executing locally");
    	}

        return getSize(fileName, ConfigurationUtil.toProperties(conf));
    }
    
    public static long getSize(String fileName, Properties properties) throws IOException {
    	DataStorage dds = new HDataStorage(properties);
        ElementDescriptor elem = dds.asElement(fileName);
       
        // recursively get all the files under this path
        ElementDescriptor[] allElems = getFileElementDescriptors(elem);
        
        long size = 0;
        
        // add up the sizes of all files found
        for (int i=0; i<allElems.length; i++) {
        	Map<String, Object> stats = allElems[i].getStatistics();
        	size += (Long) (stats.get(ElementDescriptor.LENGTH_KEY));
        }
        
        return size;
    }
    
    private static InputStream openDFSFile(ElementDescriptor elem) throws IOException{
        ElementDescriptor[] elements = null;
        
        if (elem.exists()) {
            try {
                if(! elem.getDataStorage().isContainer(elem.toString())) {
                    if (elem.systemElement())
                        throw new IOException ("Attempt is made to open system file " + elem.toString());
                    return elem.open();
                }
            }
            catch (DataStorageException e) {
                throw new IOException("Failed to determine if elem=" + elem + " is container", e);
            }
            
            // elem is a directory - recursively get all files in it
            elements = getFileElementDescriptors(elem);
        } else {
            // It might be a glob
            if (!globMatchesFiles(elem, elem.getDataStorage())) {
                throw new IOException(elem.toString() + " does not exist");
            } else {
                elements = getFileElementDescriptors(elem); 
                return new DataStorageInputStreamIterator(elements);
                
            }
        }
        
        return new DataStorageInputStreamIterator(elements);
    }
    
    /**
     * recursively get all "File" element descriptors present in the input element descriptor
     * @param elem input element descriptor
     * @return an array of Element descriptors for files present (found by traversing all levels of dirs)
     *  in the input element descriptor
     * @throws DataStorageException
     */
    private static ElementDescriptor[] getFileElementDescriptors(ElementDescriptor elem) throws DataStorageException {
        DataStorage store = elem.getDataStorage();
        ElementDescriptor[] elems = store.asCollection(elem.toString());
        // elems could have directories in it, if so
        // get the files out so that it contains only files
        List<ElementDescriptor> paths = new ArrayList<ElementDescriptor>();
        List<ElementDescriptor> filePaths = new ArrayList<ElementDescriptor>();
        for (int m = 0; m < elems.length; m++) {
            paths.add(elems[m]);
        }
        for (int j = 0; j < paths.size(); j++) {
            ElementDescriptor fullPath = store.asElement(store
                    .getActiveContainer(), paths.get(j));
            // Skip hadoop's private/meta files ...
            if (fullPath.systemElement()) {
                continue;
            }
            
            if (fullPath instanceof ContainerDescriptor) {
                for (ElementDescriptor child : ((ContainerDescriptor) fullPath)) {
                    paths.add(child);
                }
                continue;
            } else {
                // this is a file, add it to filePaths
                filePaths.add(fullPath);
            }
        }
        elems = new ElementDescriptor[filePaths.size()];
        filePaths.toArray(elems);
        return elems;
    }
    
    private static InputStream openLFSFile(ElementDescriptor elem) throws IOException{
        // IMPORTANT NOTE: Currently we use HXXX classes to represent
        // files and dirs in local mode - so we can just delegate this
        // call to openDFSFile(elem). When we have true local mode files
        // and dirs THIS WILL NEED TO CHANGE
        return openDFSFile(elem);
    }
    
    /**
     * This function returns an input stream to a local file system file or
     * a file residing on Hadoop's DFS
     * @param fileName The filename to open
     * @param execType execType indicating whether executing in local mode or MapReduce mode (Hadoop)
     * @param storage The DataStorage object used to open the fileSpec
     * @return InputStream to the fileSpec
     * @throws IOException
     * @deprecated Use {@link #open(String, PigContext)} instead
     */
    @Deprecated
    static public InputStream open(String fileName, ExecType execType, DataStorage storage) throws IOException {
        fileName = checkDefaultPrefix(execType, fileName);
        if (!fileName.startsWith(LOCAL_PREFIX)) {
            ElementDescriptor elem = storage.asElement(fullPath(fileName, storage));
            return openDFSFile(elem);
        }
        else {
            fileName = fileName.substring(LOCAL_PREFIX.length());
            ElementDescriptor elem = storage.asElement(fullPath(fileName, storage));
            return openLFSFile(elem);
        }
    }
    
    /**
     * @deprecated Use {@link #fullPath(String, PigContext)} instead
     */
    @Deprecated
    public static String fullPath(String fileName, DataStorage storage) {
        String fullPath;
        try {
            if (fileName.charAt(0) != '/') {
                ElementDescriptor currentDir = storage.getActiveContainer();
                ElementDescriptor elem = storage.asElement(currentDir.toString(), fileName);
                
                fullPath = elem.toString();
            } else {
                fullPath = fileName;
            }
        } catch (DataStorageException e) {
            fullPath = fileName;
        }
        return fullPath;
    }
    
    static public InputStream open(String fileSpec, PigContext pigContext) throws IOException {
        fileSpec = checkDefaultPrefix(pigContext.getExecType(), fileSpec);
        if (!fileSpec.startsWith(LOCAL_PREFIX)) {
            ElementDescriptor elem = pigContext.getDfs().asElement(fullPath(fileSpec, pigContext));
            return openDFSFile(elem);
        }
        else {
            fileSpec = fileSpec.substring(LOCAL_PREFIX.length());
            //buffering because we only want buffered streams to be passed to load functions.
            /*return new BufferedInputStream(new FileInputStream(fileSpec));*/
            ElementDescriptor elem = pigContext.getLfs().asElement(fullPath(fileSpec, pigContext));
            return openLFSFile(elem);
        }
    }
    
    /**
     * @param fileSpec
     * @param offset
     * @param pigContext
     * @return SeekableInputStream
     * @throws IOException
     * 
     * This is an overloaded version of open where there is a need to seek in stream. Currently seek is supported
     * only in file, not in directory or glob.
     */
    static public SeekableInputStream open(String fileSpec, long offset, PigContext pigContext) throws IOException {
        
        fileSpec = checkDefaultPrefix(pigContext.getExecType(), fileSpec);
        
        ElementDescriptor elem;
        if (!fileSpec.startsWith(LOCAL_PREFIX)) 
            elem = pigContext.getDfs().asElement(fullPath(fileSpec, pigContext));
                
        else{
            fileSpec = fileSpec.substring(LOCAL_PREFIX.length());
            elem = pigContext.getLfs().asElement(fullPath(fileSpec, pigContext));            
        }
        
        if (elem.exists() && (!elem.getDataStorage().isContainer(elem.toString()))) {
            try {
                if (elem.systemElement())
                    throw new IOException ("Attempt is made to open system file " + elem.toString());
                
                SeekableInputStream sis = elem.sopen();
                sis.seek(offset, FLAGS.SEEK_SET);
                return sis;
            }
            catch (DataStorageException e) {
                throw new IOException("Failed to determine if elem=" + elem + " is container", e);
            }
        }
        // Either a directory or a glob.
        else
            throw new IOException("Currently seek is supported only in a file, not in glob or directory.");
    }
    
    static public OutputStream create(String fileSpec, PigContext pigContext) throws IOException{
        return create(fileSpec,false,pigContext);
    }

    static public OutputStream create(String fileSpec, boolean append, PigContext pigContext) throws IOException {
        fileSpec = checkDefaultPrefix(pigContext.getExecType(), fileSpec);
        if (!fileSpec.startsWith(LOCAL_PREFIX)) {
            ElementDescriptor elem = pigContext.getDfs().asElement(fileSpec);
            return elem.create();
        }
        else {
            fileSpec = fileSpec.substring(LOCAL_PREFIX.length());

            // TODO probably this should be replaced with the local file system
            File f = (new File(fileSpec)).getParentFile();
            if (f!=null){
                boolean res = f.mkdirs();
                if (!res)
                    log.warn("FileLocalizer.create: failed to create " + f);
            }
            
            return new FileOutputStream(fileSpec,append);
        }
    }
    
    static public boolean delete(String fileSpec, PigContext pigContext) throws IOException{
        fileSpec = checkDefaultPrefix(pigContext.getExecType(), fileSpec);
        if (!fileSpec.startsWith(LOCAL_PREFIX)) {
            ElementDescriptor elem = pigContext.getDfs().asElement(fileSpec);
            elem.delete();
            return true;
        }
        else {
            fileSpec = fileSpec.substring(LOCAL_PREFIX.length());
            boolean ret = true;
            // TODO probably this should be replaced with the local file system
            File f = (new File(fileSpec));
            // TODO this only deletes the file. Any dirs createdas a part of this
            // are not removed.
            if (f!=null){
                ret = f.delete();
            }
            
            return ret;
        }
    }

    static Random      r           = new Random();

    /**
     * Thread local toDelete Stack to hold descriptors to be deleted upon calling
     * deleteTempFiles. Use the toDelete() method to access this stack.
     */
    private static ThreadLocal<Stack<ElementDescriptor>> toDelete =
        new ThreadLocal<Stack<ElementDescriptor>>() {

        protected Stack<ElementDescriptor> initialValue() {
            return new Stack<ElementDescriptor>();
        }
    };

    /**
     * Thread local relativeRoot ContainerDescriptor. Do not access this object
     * directly, since it's lazy initialized in the relativeRoot(PigContext)
     * method, which should be used instead.
     */
    private static ThreadLocal<ContainerDescriptor> relativeRoot =
        new ThreadLocal<ContainerDescriptor>() {
    };

    /**
     * Convenience accessor method to the toDelete Stack bound to this thread.
     * @return A Stack of ElementDescriptors that should be deleted.
     */
    private static Stack<ElementDescriptor> toDelete() {
        return toDelete.get();
    }

    /**
     * This method is only used by test code to reset state.
     * @param initialized
     */
    public static void setInitialized(boolean initialized) {
        if (!initialized) {
            relativeRoot.set(null);
        }
    }

    /**
     * Accessor method to get the root ContainerDescriptor used for temporary
     * files bound to this thread. Calling this method lazy-initialized the
     * relativeRoot object.
     *
     * @param pigContext
     * @return
     * @throws DataStorageException
     */
    private static synchronized ContainerDescriptor relativeRoot(final PigContext pigContext)
            throws DataStorageException {

        if (relativeRoot.get() == null) {
            String tdir= pigContext.getProperties().getProperty("pig.temp.dir", "/tmp");
            relativeRoot.set(pigContext.getDfs().asContainer(tdir + "/temp" + r.nextInt()));
            //toDelete().push(relativeRoot.get());
        }

        return relativeRoot.get();
    }

    public static void deleteTempFiles() {
        while (!toDelete().empty()) {
            try {
                ElementDescriptor elem = toDelete().pop();
                elem.delete();
            } 
            catch (IOException e) {
                log.error(e);
            }
        }
        setInitialized(false);
    }

    /**
     * @deprecated Use {@link #getTemporaryPath(PigContext)} instead
     */
    @Deprecated
    public static synchronized ElementDescriptor 
        getTemporaryPath(ElementDescriptor relative, 
                         PigContext pigContext) throws IOException {
        if (relative == null) {
            relative = relativeRoot(pigContext);
        }
        if (!relativeRoot(pigContext).exists()) {
            relativeRoot(pigContext).create();
        }
        ElementDescriptor elem= 
            pigContext.getDfs().asElement(relative.toString(), "tmp" + r.nextInt());
        //toDelete().push(elem);
        return elem;
    }

    public static Path getTemporaryPath(PigContext pigContext) throws IOException {
        ElementDescriptor relative = relativeRoot(pigContext);
    
        if (!relativeRoot(pigContext).exists()) {
            relativeRoot(pigContext).create();
        }
        ElementDescriptor elem= 
            pigContext.getDfs().asElement(relative.toString(), "tmp" + r.nextInt());
        //toDelete().push(elem);
        return ((HPath)elem).getPath();
    }
    
    public static String hadoopify(String filename, PigContext pigContext) throws IOException {
        if (filename.startsWith(LOCAL_PREFIX)) {
            filename = filename.substring(LOCAL_PREFIX.length());
        }
        
        ElementDescriptor localElem =
            pigContext.getLfs().asElement(filename);
            
        if (!localElem.exists()) {
            throw new FileNotFoundException(filename);
        }
            
        ElementDescriptor distribElem = 
            getTemporaryPath(null, pigContext);
    
        int suffixStart = filename.lastIndexOf('.');
        if (suffixStart != -1) {
            distribElem = pigContext.getDfs().asElement(distribElem.toString() +
                    filename.substring(suffixStart));
        }
            
        // TODO: currently the copy method in Data Storage does not allow to specify overwrite
        //       so the work around is to delete the dst file first, if it exists
        if (distribElem.exists()) {
            distribElem.delete();
        }
        localElem.copy(distribElem, null, false);
            
        return distribElem.toString();
    }

    public static String fullPath(String filename, PigContext pigContext) throws IOException {
        try {
            if (filename.charAt(0) != '/') {
                ElementDescriptor currentDir = pigContext.getDfs().getActiveContainer();
                ElementDescriptor elem = pigContext.getDfs().asElement(currentDir.toString(),
                                                                                  filename);
                
                return elem.toString();
            }
            return filename;
        }
        catch (DataStorageException e) {
            return filename;
        }
    }

    public static boolean fileExists(String filename, PigContext context)
            throws IOException {
        return fileExists(filename, context.getFs());
    }

    /**
     * @deprecated Use {@link #fileExists(String, PigContext)} instead
     */
    @Deprecated 
    public static boolean fileExists(String filename, DataStorage store)
            throws IOException {
        ElementDescriptor elem = store.asElement(filename);
        return elem.exists() || globMatchesFiles(elem, store);
    }

    public static boolean isFile(String filename, PigContext context)
    throws IOException {
        return !isDirectory(filename, context.getDfs());
    }

    /**
     * @deprecated Use {@link #isFile(String, PigContext)} instead 
     */
    @Deprecated
    public static boolean isFile(String filename, DataStorage store)
    throws IOException {
        return !isDirectory(filename, store);
    }

    public static boolean isDirectory(String filename, PigContext context)
    throws IOException {
        return isDirectory(filename, context.getDfs());
    }

    /**
    * @deprecated Use {@link #isDirectory(String, PigContext)} instead.
    */
    @Deprecated
    public static boolean isDirectory(String filename, DataStorage store)
    throws IOException {
        ElementDescriptor elem = store.asElement(filename);
        return (elem instanceof ContainerDescriptor);
    }

    private static boolean globMatchesFiles(ElementDescriptor elem,
                                            DataStorage fs)
            throws IOException
    {
        try {
            // Currently, if you give a glob with non-special glob characters, hadoop
            // returns an array with your file name in it.  So check for that.
            ElementDescriptor[] elems = fs.asCollection(elem.toString());
            switch (elems.length) {
            case 0:
                return false;
    
            case 1:
                return !elems[0].equals(elem);
    
            default:
                return true;
            }
        }
        catch (DataStorageException e) {
            throw e;
        }
    }

    public static Random getR() {
        return r;
    }

    public static void setR(Random r) {
        FileLocalizer.r = r;
    }
    
    /**
     * Convert path from Windows convention to Unix convention. Invoked under
     * cygwin.
     * 
     * @param path
     *            path in Windows convention
     * @return path in Unix convention, null if fail
     */
    static public String parseCygPath(String path, int style) {
        String[] command; 
        if (style==STYLE_WINDOWS)
            command = new String[] { "cygpath", "-w", path };
        else
            command = new String[] { "cygpath", "-u", path };
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(command);
        } catch (IOException e) {
            return null;
        }
        int exitVal = 0;
        try {
            exitVal = p.waitFor();
        } catch (InterruptedException e) {
            return null;
        }
        if (exitVal != 0)
            return null;
        String line = null;
        BufferedReader br = null;
        try {
            InputStreamReader isr = new InputStreamReader(p.getInputStream());
            br = new BufferedReader(isr);
            line = br.readLine();
            isr.close();
        } catch (IOException e) {
            return null;
        } finally {
            if (br != null) try {br.close();} catch (Exception e) {}
        }
        return line;
    }
    
    static File localTempDir = null;
    static {
        File f;
        boolean success = true;
        try {
            f = File.createTempFile("pig", "tmp");
            success &= f.delete();
            success &= f.mkdir();
            localTempDir = f;
            localTempDir.deleteOnExit();
        } catch (IOException e) {
        }
        if (!success) {
          throw new RuntimeException("Error creating FileLocalizer temp directory.");
        }
    }    
    
    public static class FetchFileRet {
        public FetchFileRet(File file, boolean didFetch) {
            this.file = file;
            this.didFetch = didFetch;
        }
        public File file;
        public boolean didFetch;
    }

    /**
     * Ensures that the passed path is on the local file system, fetching it 
     * to the java.io.tmpdir if necessary. If pig.jars.relative.to.dfs is true
     * and dfs is not null, then a relative path is assumed to be relative to the passed 
     * dfs active directory. Else they are assumed to be relative to the local working
     * directory.
     */
    public static FetchFileRet fetchFile(Properties properties, String filePath) throws IOException {
        // Create URI from String.
        URI fileUri = null;
        try {
            fileUri = new URI(filePath);
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
        // If URI is a local file, verify it exists and return.
        if (((!"true".equals(properties.getProperty("pig.jars.relative.to.dfs"))) && (fileUri.getScheme() == null))
                || "file".equalsIgnoreCase(fileUri.getScheme())
                || "local".equalsIgnoreCase(fileUri.getScheme())) {
            File res = new File(fileUri.getPath());
            if (!res.exists()) {
                throw new ExecException("Local file '" + filePath + "' does not exist.", 101, PigException.INPUT);
            }
            return new FetchFileRet(res, false);
        } else {
            
            Path src = new Path(fileUri.getPath());
            File parent = (localTempDir != null) ? localTempDir : new File(System.getProperty("java.io.tmpdir")); 
            File dest = new File(parent, src.getName());
            dest.deleteOnExit();
            try {
                Configuration configuration = new Configuration();
                ConfigurationUtil.mergeConf(configuration, ConfigurationUtil.toConfiguration(properties));
                FileSystem srcFs = FileSystem.get(fileUri, configuration);
                srcFs.copyToLocalFile(src, new Path(dest.getAbsolutePath()));
            } catch (IOException e) {
                throw new ExecException("Could not copy " + filePath + " to local destination " + dest, 101, PigException.INPUT, e);
            }
            return new FetchFileRet(dest, true);
        }
    }
}
