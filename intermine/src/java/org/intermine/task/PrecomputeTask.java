package org.intermine.task;

/*
 * Copyright (C) 2002-2005 FlyMine
 *
 * This code may be freely distributed and modified under the
 * terms of the GNU Lesser General Public Licence.  This should
 * be distributed with the code.  See the LICENSE file for more
 * information or http://www.gnu.org/copyleft/lesser.html.
 *
 */

import java.util.Properties;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Collection;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.TreeSet;
import java.util.TreeMap;
import java.util.Iterator;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.IOException;
import java.io.InputStreamReader;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.Task;

import org.intermine.objectstore.query.iql.IqlQuery;
import org.intermine.objectstore.query.Query;
import org.intermine.objectstore.query.QueryClass;
import org.intermine.objectstore.query.QueryField;
import org.intermine.objectstore.query.QueryReference;
import org.intermine.objectstore.query.QueryObjectReference;
import org.intermine.objectstore.query.QueryCollectionReference;
import org.intermine.objectstore.query.ContainsConstraint;
import org.intermine.objectstore.query.ConstraintOp;
import org.intermine.objectstore.query.ResultsInfo;
import org.intermine.objectstore.query.QueryCloner;
import org.intermine.objectstore.query.QueryHelper;
import org.intermine.objectstore.ObjectStore;
import org.intermine.objectstore.ObjectStoreFactory;
import org.intermine.objectstore.ObjectStoreException;
import org.intermine.objectstore.query.Results;
import org.intermine.objectstore.intermine.ObjectStoreInterMineImpl;
import org.intermine.objectstore.ObjectStoreSummary;
import org.intermine.metadata.Model;
import org.intermine.metadata.ClassDescriptor;
import org.intermine.metadata.FieldDescriptor;
import org.intermine.metadata.ReferenceDescriptor;
import org.intermine.util.TypeUtil;

import org.intermine.web.TemplateQueryBinding;

import org.apache.log4j.Logger;

/**
 * A Task that reads a list of queries from a properties file (eg. testmodel_precompute.properties)
 * and calls ObjectStoreInterMineImpl.precompute() using the Query.
 *
 * @author Kim Rutherford
 */

public class PrecomputeTask extends Task
{
    private static final Logger LOG = Logger.getLogger(PrecomputeTask.class);

    protected String alias;
    protected String summaryPropertiesFile;
    protected boolean testMode;
    protected int minRows = -1;
    // set by readProperties()
    protected Properties precomputeProperties = null;
    protected ObjectStoreSummary oss = null;
    protected ObjectStore os = null;
    private static final String TEST_QUERY_PREFIX = "test.query.";
    private boolean selectAllFields = true;   // put all available fields on the select list
    private boolean createAllOrders = false;  // create same table with all possible orders

    /**
     * Set the ObjectStore alias
     * @param alias the ObjectStore alias
     */
    public void setAlias(String alias) {
        this.alias = alias;
    }

    /**
     * Set the name of the ObjectStore summary properties file.
     * @param summaryPropertiesFile the new summaryPropertiesFile
     */
    public void setSummaryPropertiesFile(String summaryPropertiesFile) {
        this.summaryPropertiesFile = summaryPropertiesFile;
    }

    /**
     * Set the minimum row count for precomputed queries.  Queries that are estimated to have less
     * than this number of rows will not be precomputed.
     * @param minRows the minimum row count
     */
    public void setMinRows(Integer minRows) {
        this.minRows = minRows.intValue();
    }

    /**
     * @see Task#execute
     */
    public void execute() throws BuildException {
        if (alias == null) {
            throw new BuildException("alias attribute is not set");
        }

        if (summaryPropertiesFile == null) {
            throw new BuildException("summaryPropertiesFile attribute is not set");
        }

        if (minRows == -1) {
            throw new BuildException("minRows attribute is not set");
        }

        ObjectStore objectStore;

        try {
            objectStore = ObjectStoreFactory.getObjectStore(alias);
        } catch (Exception e) {
            throw new BuildException("Exception while creating ObjectStore", e);
        }

        if (!(objectStore instanceof ObjectStoreInterMineImpl)) {
            throw new BuildException(alias + " isn't an ObjectStoreInterMineImpl");
        }

        oss = createObjectStoreSummary();

        precomputeModel(objectStore, oss);
    }


    /**
     * Create precomputed tables for the given ObjectStore.  This method is also called from
     * PrecomputeTaskTest.
     * @param os the ObjectStore to precompute in
     * @param oss the ObjectStoreSummary for os
     */
    protected void precomputeModel(ObjectStore os, ObjectStoreSummary oss) {
        this.oss = oss;
        this.os = os;

        readProperties();

        if (testMode) {
            PrintStream outputStream = System.out;
            outputStream.println("Starting tests");
            // run and ignore so that the results are cached for the next test
            runTestQueries();

            long start = System.currentTimeMillis();
            outputStream.println("Running tests before precomputing");
            runTestQueries();
            outputStream.println("tests took: " + (System.currentTimeMillis() - start) / 1000
                                 + " seconds");
        }

        Map pq = getPrecomputeQueries();
        LOG.info("pq.size(): " + pq.size());
        Iterator iter = pq.entrySet().iterator();

        while (iter.hasNext()) {
            Map.Entry entry = (Map.Entry) iter.next();
            String key = (String) entry.getKey();

            List queries = (List) entry.getValue();
            LOG.debug("queries: " + queries.size());
            Iterator queriesIter = queries.iterator();
            while (queriesIter.hasNext()) {
                Query query = (Query) queriesIter.next();

                LOG.info("key: " + key);

                ResultsInfo resultsInfo;

                try {
                    resultsInfo = os.estimate(query);
                } catch (ObjectStoreException e) {
                    throw new BuildException("Exception while calling ObjectStore.estimate()", e);
                }

                if (resultsInfo.getRows() >= minRows) {
                    LOG.info("precomputing " + key + " - " + query);
                    precompute(os, query);

                    if (testMode) {
                        PrintStream outputStream = System.out;
                        long start = System.currentTimeMillis();
                        outputStream.println("Running tests after precomputing " + key + ": "
                                             + query);
                        runTestQueries();
                        outputStream.println("tests took: "
                                             + (System.currentTimeMillis() - start) / 1000
                                             + " seconds");
                    }
                }
            }
        }

        if (testMode) {
            PrintStream outputStream = System.out;
            long start = System.currentTimeMillis();
            outputStream.println("Running tests after all precomputes");
            runTestQueries();
            outputStream.println("tests took: "
                                 + (System.currentTimeMillis() - start) / 1000
                                 + " seconds");
        }
    }


    /**
     * Call ObjectStoreInterMineImpl.precompute() with the given Query.
     * @param os the ObjectStore to call precompute() on
     * @param query the query to precompute
     * @param indexes the index QueryNodes
     * @throws BuildException if the query cannot be precomputed.
     */
    protected void precompute(ObjectStore os, Query query, Collection indexes)
        throws BuildException {
        long start = System.currentTimeMillis();

        try {
            ((ObjectStoreInterMineImpl) os).precompute(query, indexes, true);
        } catch (ObjectStoreException e) {
            throw new BuildException("Exception while precomputing query: " + query
                    + " with indexes " + indexes, e);
        }

        LOG.info("precompute(indexes) of took "
                 + (System.currentTimeMillis() - start) / 1000
                 + " seconds for: " + query);
    }


    /**
     * Call ObjectStoreInterMineImpl.precompute() with the given Query.
     * @param os the ObjectStore to call precompute() on
     * @param query the query to precompute
     * @throws BuildException if the query cannot be precomputed.
     */
    protected void precompute(ObjectStore os, Query query) throws BuildException {
        long start = System.currentTimeMillis();

        try {
            ((ObjectStoreInterMineImpl) os).precompute(query, true);
        } catch (ObjectStoreException e) {
            throw new BuildException("Exception while precomputing query: " + query, e);
        }

        LOG.info("precompute() of took "
                 + (System.currentTimeMillis() - start) / 1000
                 + " seconds for: " + query);
    }


    /**
     * Get the built-in template queries.
     * @return Map from template name to TemplateQuery
     * @throws BuildException if an IO error occurs loading the template queries
     */
    protected Map getPrecomputeTemplateQueries() throws BuildException {
        TemplateQueryBinding binding = new TemplateQueryBinding();
        InputStream sin
                = PrecomputeTask.class.getClassLoader().getResourceAsStream("template-queries.xml");
        Map templates = binding.unmarshal(new InputStreamReader(sin));
        return templates;
    }


    /**
     * Get a Map of keys (from the precomputeProperties file) to Query objects to precompute.
     * @return a Map of keys to Query objects
     * @throws BuildException if the query cannot be constructed (for example when a class or the
     * collection doesn't exist
     */
    protected Map getPrecomputeQueries() throws BuildException {
        Map returnMap = new TreeMap();

        // TODO - read selectAllFields and createAllOrders from properties

        // TODO - property to not create empty tables

        Iterator iter = new TreeSet(precomputeProperties.keySet()).iterator();

        while (iter.hasNext()) {
            String precomputeKey = (String) iter.next();

            String value = (String) precomputeProperties.get(precomputeKey);

            if (precomputeKey.startsWith("precompute.query")) {
                String iqlQueryString = value;
                Query query = parseQuery(iqlQueryString, precomputeKey);
                List list = new ArrayList();
                list.add(query);
                returnMap.put(precomputeKey, list);
            } else {
                if (precomputeKey.startsWith("precompute.constructquery")) {
                    try {
                        List constructedQueries = constructQueries(value, createAllOrders);
                        returnMap.put(precomputeKey, constructedQueries);
                    } catch (Exception e) {
                        throw new BuildException(e);
                    }
                } else {
                    if (!precomputeKey.startsWith(TEST_QUERY_PREFIX)) {
                        throw new BuildException("unknown key in properties file "
                                                 + getPropertiesFileName());
                    }
                }
            }
        }
        return returnMap;
    }


    /**
     * Given a class return a set with the unqualified class name in and if preceded by
     * a '+' also the unqualified names of all subclasses.
     * @param clsName an unqullified class name
     * @return a set of class names
     */
    protected Set getClassNames(String clsName) {
        Model model = os.getModel();

        boolean useSubClasses = false;
        if (clsName.startsWith("+")) {
            clsName = clsName.substring(1);
            useSubClasses = true;
        }

        ClassDescriptor cld = model.getClassDescriptorByName(model.getPackageName()
                                                             + "." + clsName);
        if (cld == null) {
            throw new BuildException("cannot find ClassDescriptor for " + clsName
                                     + " (read name from "
                                     + getPropertiesFileName() + ")");
        }

        Set clsNames = new LinkedHashSet();
        clsNames.add(clsName);

        if (useSubClasses) {
            Set clds = model.getAllSubs(cld);
            Iterator cldIter = clds.iterator();
            while (cldIter.hasNext()) {
                clsNames.add(TypeUtil.unqualifiedName(((ClassDescriptor)
                                                       cldIter.next()).getName()));
            }
        }
        return clsNames;
    }


    /**
     * Path should be of the form: Class1 ref1 Class2 ref2 Class3
     * Where the number of elements is greater than one and an odd number.  Check
     * that all classes anf references are valid in the model.
     * @param path the path string
     * @throws IllegalArgumentException if path not valid
     */
    protected void validatePath(String path) {
        Model model = os.getModel();

        // must be more than one element and odd number
        String[] queryBits = path.split("[ \t]");
        if (!(queryBits.length > 1) || (queryBits.length % 2 == 0)) {
            throw new IllegalArgumentException("Construct query path does not have valid "
                                               + " number of elements: " + path);
        }

        for (int i = 0; i + 2 < queryBits.length; i += 2) {
            String start = model.getPackageName() + "." + queryBits[i];
            String refName = queryBits[i + 1];
            String end = model.getPackageName() + "." + queryBits[i + 2];

            if (!model.hasClassDescriptor(start)) {
                throw new IllegalArgumentException("Class not found in model: " + start);
            } else if (!model.hasClassDescriptor(end)) {
                throw new IllegalArgumentException("Class not found in model: " + end);
            }

            ClassDescriptor startCld = model.getClassDescriptorByName(start);
            ReferenceDescriptor rd = startCld.getReferenceDescriptorByName(refName);
            if ((startCld.getReferenceDescriptorByName(refName, true) == null)
                && (startCld.getCollectionDescriptorByName(refName, true) == null)) {
                throw new IllegalArgumentException("Cannot find descriptor for " + refName
                                         + " in " + startCld.getName());
            }
            // TODO check type of end vs. referenced type
        }
    }


    /**
     * Create queries for given path.  If path has a '+' next to any class then
     * expand to include all subclasses.
     * @param path the path to construct a query for
     * @param createAllOrders if true then create a query for all possible orders of QueryClass
     * objects on the from list of the query
     * @return a list of queries
     * @throws ClassNotFoundException if problem processing path
     * @throws IllegalArgumentException if problem processing path
     */
    protected List constructQueries(String path, boolean createAllOrders)
        throws ClassNotFoundException, IllegalArgumentException {

        List queries = new ArrayList();

        // expand '+' to all subclasses in path
        Set paths = expandPath(path);
        Iterator pathIter = paths.iterator();
        while (pathIter.hasNext()) {
            String nextPath = (String) pathIter.next();
            Query q = constructQuery(nextPath);
            if (createAllOrders) {
                queries.addAll(getOrderedQueries(q));
            } else {
                queries.add(q);
            }
        }
        return queries;
    }


    /**
     * Given a path return a set of paths replacing a path with a '+' preceding a class
     * name with an additional path for every subclass of that class.
     * @param path the path to expand
     * @return a Set of paths
     */
    protected Set expandPath(String path) {
        Set paths = new LinkedHashSet();

        String clsName;
        String refName = "";
        int refEnd = 0;
        if (path.indexOf(' ') != -1) {
            int clsEnd = path.indexOf(' ');
            clsName = path.substring(0, clsEnd);
            refEnd = path.indexOf(' ', clsEnd + 1);
            refName = path.substring(clsEnd, refEnd);
        } else {
            // at end, this is last clsName
            clsName = path;
        }

        Set subs = getClassNames(clsName);
        Iterator subIter = subs.iterator();
        while (subIter.hasNext()) {
            String subName = (String) subIter.next();
            Set nextPaths = new LinkedHashSet();
            if (refName != "") {
                nextPaths.addAll(expandPath(path.substring(refEnd + 1).trim()));
            } else {
                nextPaths.addAll(subs);
                return nextPaths;
            }
            Iterator pathIter = nextPaths.iterator();
            while (pathIter.hasNext()) {
                String nextPath = (String) pathIter.next();
                paths.add((subName + refName + " " + nextPath).trim());
            }
        }
        return paths;
    }


    /**
     * Construct an objectstore query represented by the given path.
     * @param path path to construct query for
     * @return the constructed query
     * @throws ClassNotFoundException if problem processing path
     * @throws IllegalArgumentException if problem processing path
     */
    protected Query constructQuery(String path) throws ClassNotFoundException,
                                                       IllegalArgumentException {
        String[] queryBits = path.split("[ \t]");

        // validate path against model
        validatePath(path);

        Model model = os.getModel();

        Query q = new Query();
        QueryClass qcLast = null;
        for (int i = 0; i + 2 < queryBits.length; i += 2) {
            QueryClass qcStart = new QueryClass(Class.forName(model.getPackageName()
                                                              + "." + queryBits[i]));
            String refName = queryBits[i + 1];
            QueryClass qcEnd = new QueryClass(Class.forName(model.getPackageName()
                                                            + "." + queryBits[i + 2]));
            if (qcLast != null) {
                qcStart = qcLast;
            }
            qcLast = addReferenceConstraint(q, qcStart, refName, qcEnd, (i == 0));
        }

        return q;
    }


    /**
     * Add a contains constraint to Query (q) from qcStart from qcEnd via reference refName.
     * Return qcEnd as it may need to be passed into mehod again as qcStart.
     * @param q the query
     * @param qcStart the QueryClass that contains the reference
     * @param refName name of reference to qcEnd
     * @param qcEnd the target QueryClass of refName
     * @param first true if this is the first constraint added - qcStart needs to be added
     * to the query
     * @return QueryClass return qcEnd
     */
    protected QueryClass addReferenceConstraint(Query q, QueryClass qcStart, String refName,
                                                QueryClass qcEnd, boolean first) {
        if (first) {
            q.addToSelect(qcStart);
            q.addFrom(qcStart);
            q.addToOrderBy(qcStart);
        }
        q.addToSelect(qcEnd);
        q.addFrom(qcEnd);
        q.addToOrderBy(qcEnd);

        // already validated against model
        ClassDescriptor startCld =
            os.getModel().getClassDescriptorByName(qcStart.getType().getName());
        FieldDescriptor fd = startCld.getFieldDescriptorByName(refName);

        QueryReference qRef;
        if (fd.isReference()) {
            qRef = new QueryObjectReference(qcStart, refName);
        } else {
            qRef = new QueryCollectionReference(qcStart, refName);
        }
        ContainsConstraint cc = new ContainsConstraint(qRef, ConstraintOp.CONTAINS, qcEnd);
        QueryHelper.addConstraint(q, cc);

        return qcEnd;
    }


    /**
     * Return a List containing clones of the given Query, but with all permutations
     * of order by for the QueryClass objects on the from list.
     * @param q the Query
     * @return clones of the Query with all permutations of orderBy
     */
    protected List getOrderedQueries(Query q) {
        List queryList = new ArrayList();

        Set permutations = permutations(q.getOrderBy().size());
        Iterator iter = permutations.iterator();
        while (iter.hasNext()) {
            Query newQuery = QueryCloner.cloneQuery(q);
            List orderBy = new ArrayList(newQuery.getOrderBy());
            newQuery.clearOrderBy();

            int[] order = (int[]) iter.next();
            for (int i = 0; i < order.length; i++) {
                newQuery.addToOrderBy((QueryClass) orderBy.get(order[i]));
            }

            queryList.add(newQuery);
        }
        return queryList;
    }


    /**
     * Add QueryFields for each of the field names in fieldNames to the given Query.
     * @param q the Query to add to
     * @param qc the QueryClass that the QueryFields should be created for
     * @param fieldNames the field names to create QueryFields for
     */
    protected void addFieldsToQuery(Query q, QueryClass qc, List fieldNames) {
        Iterator fieldNameIter = fieldNames.iterator();

        while (fieldNameIter.hasNext()) {
            String fieldName = (String) fieldNameIter.next();
            if (fieldName.equals("id")) {
                continue;
            }
            QueryField qf = new QueryField(qc, fieldName);
            q.addToSelect(qf);
        }
    }

    /**
     * For a given IQL query, return a Query object.
     * @param iqlQueryString the IQL String
     * @param key the key from the properties file
     * @return a Query object
     * @throws BuildException if the IQL String cannot be parsed.
     */
    protected Query parseQuery(String iqlQueryString, String key) throws BuildException {
        IqlQuery iqlQuery = new IqlQuery(iqlQueryString, os.getModel().getPackageName());

        try {
            return iqlQuery.toQuery();
        } catch (IllegalArgumentException e) {
            throw new BuildException("Exception while parsing query: " + key
                                     + " = " + iqlQueryString, e);
        }
    }

    /**
     * Run all the test queries specified in precomputeProperties.
     * @throws BuildException if there is an error while running the queries.
     */
    protected void runTestQueries() throws BuildException {
        TreeMap sortedPrecomputeProperties = new TreeMap(precomputeProperties);
        Iterator iter = sortedPrecomputeProperties.entrySet().iterator();

        while (iter.hasNext()) {
            Map.Entry entry = (Map.Entry) iter.next();

            String testqueryKey = (String) entry.getKey();
            if (testqueryKey.startsWith(TEST_QUERY_PREFIX)) {
                String iqlQueryString = (String) entry.getValue();
                Query query = parseQuery(iqlQueryString, testqueryKey);

                long start = System.currentTimeMillis();
                PrintStream outputStream = System.out;
                outputStream.println("  running test " + testqueryKey + ":");
                Results results;
                try {
                    results = os.execute(query);
                } catch (ObjectStoreException e) {
                    throw new BuildException("problem executing " + testqueryKey + " test", e);
                }
                int resultsSize = results.size();
                outputStream.println("  got size " + resultsSize + " in "
                                   + (System.currentTimeMillis() - start) / 1000 + " seconds");
                if (resultsSize > 0) {
                    start = System.currentTimeMillis();
                    List resultsRow1 = (List) results.get(0);
                    outputStream.println("  first row in "
                                         + (System.currentTimeMillis() - start) / 1000
                                         + " seconds");
                    start = System.currentTimeMillis();
                    List resultsRow2 = (List) results.get(resultsSize - 1);
                    outputStream.println("  last row in "
                                         + (System.currentTimeMillis() - start) / 1000
                                         + " seconds");
                }
            }
        }
    }


    /**
     * Set precomputeProperties by reading from propertiesFileName.
     * @throws BuildException if the file cannot be read.
     */
    protected void readProperties() throws BuildException {
        String propertiesFileName = getPropertiesFileName();

        try {
            InputStream is =
                PrecomputeTask.class.getClassLoader().getResourceAsStream(propertiesFileName);

            if (is == null) {
                throw new BuildException("Cannot find " + propertiesFileName
                                         + " in the class path");
            }

            precomputeProperties = new Properties();
            precomputeProperties.load(is);
        } catch (IOException e) {
            throw new BuildException("Exception while reading properties from "
                                     + propertiesFileName , e);
        }
    }


    /**
     * Create a ObjectStoreSummary from the summaryPropertiesFile.
     * @return a new ObjectStoreSummary
     */
    protected ObjectStoreSummary createObjectStoreSummary() {
        try {
            InputStream summaryPropertiesStream =
                PrecomputeTask.class.getClassLoader().getResourceAsStream(summaryPropertiesFile);

            if (summaryPropertiesStream == null) {
                throw new BuildException("Cannot find " + summaryPropertiesFile);
            }

            Properties summaryProperties = new Properties();
            summaryProperties.load(summaryPropertiesStream);

            return new ObjectStoreSummary(summaryProperties);
        } catch (IOException e) {
            throw new BuildException("Exception while reading properties from "
                                     + summaryPropertiesFile , e);
        }
    }


    /**
     * Return the name of the properties file that passed to the constructor.
     * @return the name of the properties file that passed to the constructor.
     */
    protected String getPropertiesFileName() {
        return os.getModel().getName() + "_precompute.properties";
    }


    /**
     * Given an integer number, n, return a Set of int arrays with all permutations
     * of numbers 0 to n.
     * @param n number of entities in ordered arrays
     * @return a set of int arrays
     */
    protected Set permutations(int n) {
        Set result = new LinkedHashSet();
        int[] array = new int[n];

        for (int i = 0; i < n; i++) {
            array[i] = i;
        }
        enumerate(result, array, n);
        return result;
    }

    private void swap(int[] array, int i, int j) {
        int tmp = array[i];
        array[i] = array[j];
        array[j] = tmp;
    }

    private void enumerate(Set result, int[] array, int n) {
        if (n == 1) {
            int[] copy = new int[array.length];
            System.arraycopy(array, 0, copy, 0, array.length);
            result.add(copy);
            return;
        }
        for (int i = 0; i < n; i++) {
            swap(array, i, n - 1);
            enumerate(result, array, n - 1);
            swap(array, i, n - 1);
        }
    }
}
