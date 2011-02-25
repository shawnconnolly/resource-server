/**
 * Licensed to Jasig under one or more contributor license
 * agreements. See the NOTICE file distributed with this work
 * for additional information regarding copyright ownership.
 * Jasig licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a
 * copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

/**
 * 
 */
package org.jasig.resource.aggr;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.io.StringWriter;
import java.io.Writer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.SystemUtils;
import org.apache.commons.lang.Validate;
import org.apache.commons.lang.builder.EqualsBuilder;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jasig.resourceserver.aggr.AggregationException;
import org.jasig.resourceserver.aggr.ResourcesDao;
import org.jasig.resourceserver.aggr.ResourcesDaoImpl;
import org.jasig.resourceserver.aggr.om.BasicInclude;
import org.jasig.resourceserver.aggr.om.Css;
import org.jasig.resourceserver.aggr.om.Included;
import org.jasig.resourceserver.aggr.om.Js;
import org.jasig.resourceserver.aggr.om.Resources;
import org.mozilla.javascript.ErrorReporter;
import org.mozilla.javascript.EvaluatorException;

import com.yahoo.platform.yui.compressor.CssCompressor;
import com.yahoo.platform.yui.compressor.JavaScriptCompressor;

/**
 * {@link ResourcesAggregator} implementation.
 * 
 * @author Nicholas Blair, npblair@wisc.edu
 *
 */
public class ResourcesAggregatorImpl implements ResourcesAggregator {
	protected final Log logger;
	
	private final static String CSS = ".aggr.min.css";
	private final static String JS = ".aggr.min.js";
	
	private final JavaScriptErrorReporterImpl JAVA_SCRIPT_ERROR_REPORTER = new JavaScriptErrorReporterImpl();
	private final ResourcesDao resourcesDao;
    private final String encoding;

	private int cssLineBreakColumnNumber = 10000;
	private int jsLineBreakColumnNumber = 10000;

	private boolean obfuscateJs = true;
	private boolean displayJsWarnings = true;
	private boolean preserveAllSemiColons = true;
	private boolean disableJsOptimizations = false;
	
	private String digestAlgorithm = "MD5";
	
	public ResourcesAggregatorImpl(Log logger, String encoding) {
	    this.logger = logger != null ? logger : LogFactory.getLog(this.getClass());
	    this.encoding = encoding;
	    this.resourcesDao = new ResourcesDaoImpl(this.logger, this.encoding);
    }
	
	public ResourcesAggregatorImpl() {
	    this(null, "UTF-8");
	}
	
    public String getDigestAlgorithm() {
        return this.digestAlgorithm;
    }
    /**
     * The algorithm to use when creating the hashed file name for aggregated resources
     */
    public void setDigestAlgorithm(String digestAlgorithm) {
        this.digestAlgorithm = digestAlgorithm;
    }


    public int getCssLineBreakColumnNumber() {
		return cssLineBreakColumnNumber;
	}
	/**
	 * Maximum line length for minified CSS files, will wrap at this length, defaults to 10000
	 * @see CssCompressor#compress(java.io.Writer, int)
	 */
	public void setCssLineBreakColumnNumber(int cssLineBreakColumnNumber) {
		this.cssLineBreakColumnNumber = cssLineBreakColumnNumber;
	}

	public int getJsLineBreakColumnNumber() {
		return jsLineBreakColumnNumber;
	}
	/**
	 * Maximum line length for minified JS files, will wrap at this length, defaults to 10000
	 * @see JavaScriptCompressor#compress(java.io.Writer, int, boolean, boolean, boolean, boolean)
	 */
	public void setJsLineBreakColumnNumber(int jsLineBreakColumnNumber) {
		this.jsLineBreakColumnNumber = jsLineBreakColumnNumber;
	}

	public boolean isObfuscateJs() {
		return obfuscateJs;
	}
	/**
	 * If the JavaScript should be munged, obfuscating local symbols, defaults to true
	 */
	public void setObfuscateJs(boolean obfuscateJs) {
		this.obfuscateJs = obfuscateJs;
	}

	public boolean isDisplayJsWarnings() {
		return displayJsWarnings;
	}
	/**
	 * If JS syntax warnings should be displayed, defaults to true
	 */
	public void setDisplayJsWarnings(boolean displayJsWarnings) {
		this.displayJsWarnings = displayJsWarnings;
	}

	public boolean isPreserveAllSemiColons() {
		return preserveAllSemiColons;
	}
	/**
	 * If unnecessary semicolons should be preserved, defaults to true
	 */
	public void setPreserveAllSemiColons(boolean preserveAllSemiColons) {
		this.preserveAllSemiColons = preserveAllSemiColons;
	}

	public boolean isDisableJsOptimizations() {
		return disableJsOptimizations;
	}
	/**
	 * Disable micro-optimizations, defaults to false
	 */
	public void setDisableJsOptimizations(boolean disableJsOptimizations) {
		this.disableJsOptimizations = disableJsOptimizations;
	}
	

	@Override
    public void aggregate(File resourcesXml, File outputBaseDirectory) throws IOException, AggregationException {
        this.aggregate(resourcesXml, outputBaseDirectory, null);
    }

    /**
	 * Aggregate the {@link Resources} object from the first {@link File} argument, placing
	 * all generated CSS and Javascript in the directory denoted in the second {@link File} argument.
	 * 
	 * Will generate an aggregated version of the resourcesXml file in the outputBaseDirectory, with the filename
	 * similar to the resourcesXml.
	 * Example:
	 * 
	 * resourcesXml filename: skin.xml; output filename: skin.aggr.xml
	 * 
	 * @see org.jasig.ResourcesAggregator.web.skin.IResourcesAggregator#aggregate(java.io.File, java.io.File)
	 * @throws IllegalArgumentException if outputBaseDirectory (2nd file argument) is not a writable directory
	 */
	@Override
	public void aggregate(File resourcesXml, File outputBaseDirectory, File sharedJavaScriptDirectory) throws IOException, AggregationException {
	    if (outputBaseDirectory != null) {
	        outputBaseDirectory.mkdirs();
	    }
        if (null == outputBaseDirectory || !outputBaseDirectory.isDirectory() || !outputBaseDirectory.canWrite()) {
            throw new IllegalArgumentException("outputBaseDirectory (" + (null == outputBaseDirectory ? null : outputBaseDirectory.getAbsolutePath()) + ") must be a directory AND writable");
        }
        
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance(this.digestAlgorithm);
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalArgumentException("Failed to create MessageDigest for algorithm '" + this.digestAlgorithm, e);
        }
		
		// parse the resourcesXml input
		final Resources original = this.resourcesDao.readResources(resourcesXml, Included.AGGREGATED);
		final File resourcesParentDir = resourcesXml.getParentFile();
		
		// aggregate CSS elements
		final CssCallback cssCallback = new CssCallback(digest, resourcesParentDir, outputBaseDirectory);
		final List<Css> cssResult = this.aggregateBasicIncludes(original.getCss(), cssCallback);

		// aggregate JS elements
		final JsCallback jsCallback = new JsCallback(digest, resourcesParentDir, outputBaseDirectory, sharedJavaScriptDirectory);
        final List<Js> jsResult = this.aggregateBasicIncludes(original.getJs(), jsCallback);

		// build aggregated form result
		final Resources aggregatedForm = new Resources();
		aggregatedForm.getCss().addAll(cssResult);
		aggregatedForm.getJs().addAll(jsResult);
		aggregatedForm.getParameter().addAll(original.getParameter());
		
		// dump aggregated form out to output directory
		final String aggregatedFormOutputFileName = this.resourcesDao.getAggregatedSkinName(resourcesXml.getName());
		
        final File aggregatedOutputFile = new File(outputBaseDirectory, aggregatedFormOutputFileName);
        this.resourcesDao.writeResources(aggregatedForm, aggregatedOutputFile);
		
		this.logger.info("Aggregated " + original.getJs().size() + 
		        " JavaScript files down to " + aggregatedForm.getJs().size() + 
		        " and " + original.getCss().size() + 
		        " CSS files down to " + aggregatedForm.getCss().size() + 
		        " for: " + resourcesXml);
	}
	
	/**
	 * Iterate over the list of {@link BasicInclude} sub-classes using the {@link AggregatorCallback#willAggregate(BasicInclude, BasicInclude)}
	 * and {@link AggregatorCallback#aggregate(Deque)} to generate an aggregated list of {@link BasicInclude} sub-classes.
	 */
	protected <T extends BasicInclude> List<T> aggregateBasicIncludes(List<T> original, AggregatorCallback<T> callback) throws IOException {
        final List<T> result = new LinkedList<T>();
        final Deque<T> currentAggregateList = new LinkedList<T>();
        for (final T originalElement : original) {
            // handle first loop iteration
            if (currentAggregateList.isEmpty()) {
                currentAggregateList.add(originalElement);
            }
            else {
                // test if 'originalElement' will aggregate with head element in currentAggregate 
                final T baseElement = currentAggregateList.getFirst();
                if (callback.willAggregate(originalElement, baseElement)) {
                    // matches current criteria, add to currentAggregate
                    currentAggregateList.add(originalElement);
                } 
                else {
                    // doesn't match criteria
                    // generate new single aggregate from currentAggregateList
                    final T aggregate = callback.aggregate(currentAggregateList);
                    if (null != aggregate) {
                        // push result
                        result.add(aggregate);
                    }
                    else {
                        this.logger.warn("Generated 0 byte aggregate from: " + generatePathList(currentAggregateList));
                    }

                    // zero out currentAggregateList
                    currentAggregateList.clear();

                    // add originalElement to empty list
                    currentAggregateList.add(originalElement);
                }
            }
        }
        
        // flush the currentAggregateList
        if (currentAggregateList.size() > 0) {
            final T aggregate = callback.aggregate(currentAggregateList);
            if (null != aggregate) {
                result.add(aggregate);
            }
            else {
                this.logger.warn("Generated 0 byte aggregate from: " + generatePathList(currentAggregateList));
            }
        }
        
        return result;
	}

	/**
	 * Aggregate the specified Deque of elements into a single element. The provided MessageDigest is used for
	 * building the file name based on the hash of the file contents. The callback is used for type specific
	 * operations.
	 */
	protected <T extends BasicInclude> T aggregateList(final MessageDigest digest, final Deque<T> elements, 
	        final File skinDirectory, final File outputRoot, final File alternateOutput, 
	        final String extension, final AggregatorCallback<T> callback) throws IOException {
	    
        if (null == elements || elements.size() == 0) {
            return null;
        }

		// reference to the head of the list
        final T headElement = elements.getFirst();
        if (elements.size() == 1 && this.resourcesDao.isAbsolute(headElement)) {
            return headElement;
        }
        
        final StringWriter aggregateWriter = new StringWriter();
        for (final T element: elements) {
            final File resourceFile = new File(skinDirectory, element.getValue());
            
            Reader resourceIn = null;
            try {
                resourceIn = new FileReader(resourceFile);
                if (element.isCompressed()) {
                    IOUtils.copy(resourceIn, aggregateWriter);
                }
                else {
                    callback.compress(resourceIn, aggregateWriter);
                }
            }
            catch (IOException e) {
                throw new IOException("Failed to read '" + resourceFile + "' for skin: " + skinDirectory, e);
            }
            finally {
                IOUtils.closeQuietly(resourceIn);
            }
            aggregateWriter.write(SystemUtils.LINE_SEPARATOR);
        }
        
        final String trimmedOutput = aggregateWriter.toString().trim();
        if (trimmedOutput.length() == 0) {
            return null;
        }
        
		final File tempFile = File.createTempFile("working.", extension);
		
		//Make sure we're working with a clean MessageDigest
		digest.reset();
		final MessageDigestOutputStream digestStream = new MessageDigestOutputStream(new FileOutputStream(tempFile), digest);
		try {
		    IOUtils.write(trimmedOutput, digestStream, this.encoding);
		}
        finally {
            IOUtils.closeQuietly(digestStream);
        }

		// temp file is created, get checksum
		final String checksum = Base64.encodeBase64URLSafeString(digest.digest());
		digest.reset();

		// create a new file name
		final String newFileName = checksum + extension;

		// Build the new file name and path
		final File aggregateOutputFile;
		if (alternateOutput == null) {
		    final String elementRelativePath = FilenameUtils.getFullPath(headElement.getValue());
	        final File directoryInOutputRoot = new File(outputRoot, elementRelativePath);
	        // create the same directory structure in the output root
	        directoryInOutputRoot.mkdirs();

	        aggregateOutputFile = new File(directoryInOutputRoot, newFileName);
		}
		else {
		    aggregateOutputFile = new File(alternateOutput, newFileName);
		}
		
		//Move the aggregate file into the correct location
		aggregateOutputFile.delete();
        FileUtils.moveFile(tempFile, aggregateOutputFile);

		final String newResultValue = RelativePath.getRelativePath(outputRoot, aggregateOutputFile);
		
		this.logAggregation(elements, newResultValue);
		
		return callback.getAggregateElement(newResultValue, elements);
	}

    /**
     * Log the result of an aggregation
     */
    protected void logAggregation(final Deque<? extends BasicInclude> elements, final String fileName) {
        if (this.logger.isDebugEnabled()) {
            final StringBuilder msg = new StringBuilder("Aggregated ")
                .append(fileName)
                .append(" from ")
                .append(generatePathList(elements));
            
            this.logger.debug(msg);
        }
    }

    /**
     * Build a string from the values of a Collection of {@link BasicInclude} elements
     */
    protected String generatePathList(final Collection<? extends BasicInclude> elements) {
        final StringBuilder msg = new StringBuilder();
        msg.append("[");
        for (final Iterator<? extends BasicInclude> elementItr = elements.iterator(); elementItr.hasNext();) {
            msg.append(elementItr.next().getValue());
            if (elementItr.hasNext()) {
                msg.append(", ");
            }
        }
        msg.append("]");
        
        return msg.toString();
    }
    
    /**
     * Similar to the {@link #equals(Object)} method, this will return
     * true if this object and the argument are "aggregatable".
     * 
     * 2 {@link Css} objects are aggregatable if and only if:
     * <ol>
     * <li>Neither object returns true for {@link #isAbsolute()}</li>
     * <li>The values of their "conditional" properties are equivalent</li>
     * <li>The values of their "media" properties are equivalent</li>
     * <li>The "paths" of their values are equivalent</li>
     * </ol>
     * 
     * The last rule mentioned above uses {@link FilenameUtils#getFullPath(String)}
     * to compare each object's value. In short, the final file name in the value's path
     * need not be equal, but the rest of the path in the value must be equal.
     * 
     * @param second
     * @return
     */
    protected boolean willAggregateWith(Css first, Css second) {
        Validate.notNull(first, "Css argument cannot be null");
        Validate.notNull(second, "Css argument cannot be null");
        
        // never can aggregate absolute Css values
        if (this.resourcesDao.isAbsolute(first) || this.resourcesDao.isAbsolute(second)) {
            return false;
        }
        
        final String firstFullPath = FilenameUtils.getFullPath(first.getValue());
        final String secondFullPath = FilenameUtils.getFullPath(second.getValue());
        
        return new EqualsBuilder()
            .append(first.getConditional(), second.getConditional())
            .append(first.getMedia(), second.getMedia())
            .append(firstFullPath, secondFullPath)
            .isEquals();
    }
    
    /**
     * Similar to the {@link #equals(Object)} method, this will return
     * true if this object and the argument are "aggregatable".
     * 
     * 2 {@link Js} objects are aggregatable if and only if:
     * <ol>
     * <li>Neither object returns true for {@link #isAbsolute()}</li>
     * <li>The values of their "conditional" properties are equivalent</li>
     * </ol>
     * 
     * The last rule mentioned above uses {@link FilenameUtils#getFullPath(String)}
     * to compare each object's value. In short, the final file name in the value's path
     * need not be equal, but the rest of the path in the value must be equal.
     * 
     * @param other
     * @return
     */
    protected boolean willAggregateWith(Js first, Js second) {
        Validate.notNull(first, "Js cannot be null");
        Validate.notNull(second, "Js cannot be null");
        
        // never aggregate absolutes
        if(this.resourcesDao.isAbsolute(first) || this.resourcesDao.isAbsolute(second)) {
            return false;
        }

        return new EqualsBuilder()
            .append(first.getConditional(), second.getConditional())
            .isEquals();
    }
    
    public interface AggregatorCallback<T extends BasicInclude> {
        public void compress(Reader reader, Writer writer) throws EvaluatorException, IOException;
        public T getAggregateElement(String location, final Deque<T> elements); 
        public T aggregate(Deque<T> list) throws IOException;
        public boolean willAggregate(T first, T second);
    }
    
    private class JsCallback implements AggregatorCallback<Js> {
        private final MessageDigest digest;
        private final File resourcesParentDir;
        private final File outputBaseDirectory;
        private final File sharedJavaScriptDirectory;
        
        public JsCallback(MessageDigest digest, File resourcesParentDir, File outputBaseDirectory, File sharedJavaScriptDirectory) {
            this.digest = digest;
            this.resourcesParentDir = resourcesParentDir;
            this.outputBaseDirectory = outputBaseDirectory;
            this.sharedJavaScriptDirectory = sharedJavaScriptDirectory;
        }

        @Override
        public void compress(Reader reader, Writer writer) throws EvaluatorException, IOException {
            final JavaScriptCompressor jsCompressor = new JavaScriptCompressor(reader, JAVA_SCRIPT_ERROR_REPORTER);
            jsCompressor.compress(writer, jsLineBreakColumnNumber, obfuscateJs, displayJsWarnings, preserveAllSemiColons, disableJsOptimizations);
        }

        @Override
        public Js getAggregateElement(String location, Deque<Js> elements) {
            final Js baseElement = elements.getFirst();
            
            final Js aggregate = new Js();
            aggregate.setValue(location);
            aggregate.setConditional(baseElement.getConditional());
            aggregate.setCompressed(true);
            
            return aggregate;
        }

        @Override
        public Js aggregate(Deque<Js> list) throws IOException {
            final File alternateOutput;
            if (sharedJavaScriptDirectory == null) {
                alternateOutput = outputBaseDirectory;
            }
            else {
                alternateOutput = sharedJavaScriptDirectory;
            }
            
            return aggregateList(digest, list, resourcesParentDir, outputBaseDirectory, alternateOutput, JS, this);
        }

        @Override
        public boolean willAggregate(Js first, Js second) {
            return willAggregateWith(first, second);
        }
    }
    
    private class CssCallback implements AggregatorCallback<Css> {
        private final MessageDigest digest;
        private final File resourcesParentDir;
        private final File outputBaseDirectory;
        
        public CssCallback(MessageDigest digest, File resourcesParentDir, File outputBaseDirectory) {
            this.digest = digest;
            this.resourcesParentDir = resourcesParentDir;
            this.outputBaseDirectory = outputBaseDirectory;
        }

        @Override
        public void compress(Reader reader, Writer writer) throws EvaluatorException, IOException {
            final CssCompressor jsCompressor = new CssCompressor(reader);
            jsCompressor.compress(writer, cssLineBreakColumnNumber);
        }

        @Override
        public Css getAggregateElement(String location, Deque<Css> elements) {
            final Css baseElement = elements.getFirst();
            
            final Css aggregate = new Css();
            aggregate.setValue(location);
            aggregate.setConditional(baseElement.getConditional());
            aggregate.setMedia(baseElement.getMedia());
            aggregate.setCompressed(true);
            
            return aggregate;
        }

        @Override
        public Css aggregate(Deque<Css> list) throws IOException {
            return aggregateList(digest, list, resourcesParentDir, outputBaseDirectory, null, CSS, this);
        }

        @Override
        public boolean willAggregate(Css first, Css second) {
            return willAggregateWith(first, second);
        }
    }

	/**
	 * {@link ErrorReporter} implementation that builds an error message and prints it using
	 * the parent class' Commons Logging {@link Log}.
	 */
	private class JavaScriptErrorReporterImpl implements ErrorReporter {
	    @Override
        public void error(String message, String sourceName, int line, String lineSource, int lineOffset) {
            logger.error("JavaScriptCompressor: " + message + ", sourceName: " + sourceName
                    + ", line: " + line + ", lineSource: " + lineSource + ", lineOffset: " + lineOffset);
        }

        @Override
        public EvaluatorException runtimeError(String message, String sourceName, int line, String lineSource,
                int lineOffset) {
            error(message, sourceName, line, lineSource, lineOffset);
            return new EvaluatorException(message, sourceName, line, lineSource, lineOffset);
        }

        @Override
        public void warning(String message, String sourceName, int line, String lineSource, int lineOffset) {
            logger.warn("JavaScriptCompressor: " + message + ", sourceName: " + sourceName
                    + ", line: " + line + ", lineSource: " + lineSource + ", lineOffset: " + lineOffset);
        }
	}
}
