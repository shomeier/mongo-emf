/*******************************************************************************
 * Copyright (c) 2010 Bryan Hunt & Ed Merks.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Bryan Hunt & Ed Merks - initial API and implementation
 *******************************************************************************/

package org.eclipselabs.mongo.emf;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.bson.types.ObjectId;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EDataType;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.EcorePackage;
import org.eclipse.emf.ecore.InternalEObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.URIConverter;
import org.eclipse.emf.ecore.resource.impl.URIHandlerImpl;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.emf.ecore.util.FeatureMap;
import org.eclipse.emf.ecore.util.FeatureMap.Entry;
import org.eclipse.emf.ecore.util.FeatureMap.Internal;
import org.eclipse.emf.ecore.util.FeatureMapUtil;
import org.eclipse.emf.ecore.xmi.XMLResource;
import org.eclipselabs.emf.query.BinaryOperation;
import org.eclipselabs.emf.query.Expression;
import org.eclipselabs.emf.query.Literal;
import org.eclipselabs.emf.query.QueryFactory;
import org.eclipselabs.emf.query.Result;
import org.eclipselabs.emf.query.util.ExpressionBuilder;
import org.eclipselabs.emf.query.util.QuerySwitch;
import org.eclipselabs.mongo.IMongoDB;
import org.eclipselabs.mongo.internal.emf.Activator;

import com.mongodb.BasicDBObject;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.DBObject;
import com.mongodb.Mongo;
import com.mongodb.MongoURI;

/**
 * This EMF URI handler interfaces to MongoDB. This URI handler can handle URIs with the "mongo"
 * scheme. The URI path must have exactly 3 segments. The URI path must be of the form
 * /database/collection/{id} where id is optional the first time the EMF object is saved. When
 * building queries, do not specify an id, but make sure path has 3 segments by placing a "/" after
 * the collection.
 * 
 * Note that if the id is not specified when the object is first saved, MongoDB will assign the id
 * and the URI of the EMF Resource will be modified to include the id in the URI. Examples of valid
 * URIs:
 * 
 * mongo://localhost/data/people/
 * mongo://localhost/data/people/4d0a3e259095b5b334a59df0
 * 
 * @author bhunt
 * 
 */
public class MongoDBURIHandlerImpl extends URIHandlerImpl
{
	/**
	 * This option can be used when saving an object the first time to indicate that the client is
	 * generating the ID by setting the value to Boolean.FALSE.
	 */
	public static final String OPTION_GENERATE_ID = "org.eclipselabs.mongo.emf.genId";

	/**
	 * This constructor can be used in an OSGi environment and will get the IMongoDB service from the
	 * bundle activator.
	 */
	public MongoDBURIHandlerImpl()
	{
		this(Activator.getInstance().getMongoDB());
	}

	/**
	 * This constructor can be used in a standalone EMF environment. The user must supply an instance
	 * of IMongoDB.
	 * 
	 * @param mongoDB the MongoDB service
	 */
	public MongoDBURIHandlerImpl(IMongoDB mongoDB)
	{
		this.mongoDB = mongoDB;
	}

	@Override
	public boolean canHandle(URI uri)
	{
		// This handler should only accept URIs with the scheme "mongo"

		return "mongo".equalsIgnoreCase(uri.scheme());
	}

	@Override
	public void delete(URI uri, Map<?, ?> options) throws IOException
	{
		// It is assumed that delete is called with the URI path /database/collection/id

		DBCollection collection = getCollection(uri);
		collection.findAndRemove(new BasicDBObject(ID_KEY, getID(uri)));
	}

	@Override
	public OutputStream createOutputStream(final URI uri, final Map<?, ?> options) throws IOException
	{
		// This function may be called with a URI path with or without an id. If an id is not specified
		// the EMF resource URI will be modified to include the id generated by MongoDB.

		return new MongoDBOutputStream()
		{
			@Override
			public void close() throws IOException
			{
				super.close();
				EObject root = resource.getContents().get(0);
				DBCollection collection = getCollection(uri);

				// We need to set up the XMLResource.URIHandler so that proxy URIs are handled properly.

				XMLResource.URIHandler uriHandler = (XMLResource.URIHandler) options.get(XMLResource.OPTION_URI_HANDLER);

				if (uriHandler == null)
					uriHandler = new org.eclipse.emf.ecore.xmi.impl.URIHandlerImpl();

				ObjectId id = getID(uri);

				// If the id was not specified, we append a dummy id to the resource URI so that all of the
				// relative proxies will be generated correctly.

				if (id == null)
					resource.setURI(resource.getURI().trimSegments(1).appendSegment("-1"));

				uriHandler.setBaseURI(resource.getURI());

				// Build a MongoDB object from the EMF object.

				DBObject dbObject = buildDBObject(collection.getDB(), root, uriHandler);

				Map<Object, Object> response = getResponse(options);
				long timeStamp = System.currentTimeMillis();
				dbObject.put(TIME_STAMP_KEY, timeStamp);
				response.put(URIConverter.RESPONSE_TIME_STAMP_PROPERTY, timeStamp);

				if (id == null)
				{
					// The id was not specified in the URI, so we are creating an object for the first time.

					collection.insert(dbObject);
					id = (ObjectId) dbObject.get(ID_KEY);

					// Since MongoDB assigns an id to the inserted object, we need to modify the EMF Resource
					// URI to include the generated id by removing the dummy id and appending the id generated
					// by MongoDB.

					URI newURI = resource.getURI().trimSegments(1).appendSegment(id.toString());

					// The EMF framework will do the actual modification of the Resource URI if the new URI is
					// set in the response options.

					response.put(URIConverter.RESPONSE_URI, newURI);
				}
				else
				{
					// The object id was specified, so we are either doing an update, or inserting a new
					// object. If the save option OPTION_GENERATE_ID is specified and set to false, we assume
					// the client is generating the id and we insert the object. Under all other conditions,
					// we update the object.

					Boolean genId = (Boolean) options.get(OPTION_GENERATE_ID);

					dbObject.put(ID_KEY, id);

					if (genId != null && !genId)
						collection.insert(dbObject);
					else
						collection.findAndModify(new BasicDBObject(ID_KEY, id), dbObject);
				}
			}
		};
	}

	public static class MongoDBOutputStream extends ByteArrayOutputStream implements URIConverter.Savable
	{
		protected Resource resource;

		@Override
		public void saveResource(Resource resource)
		{
			this.resource = resource;
		}
	}

	@Override
	public InputStream createInputStream(final URI uri, final Map<?, ?> options) throws IOException
	{
		return new MongoDBInputStream()
		{
			@Override
			public void loadResource(Resource resource) throws IOException
			{
				// We need to set up the XMLResource.URIHandler so that proxy URIs are handled properly.

				XMLResource.URIHandler uriHandler = (XMLResource.URIHandler) options.get(XMLResource.OPTION_URI_HANDLER);

				if (uriHandler == null)
					uriHandler = new org.eclipse.emf.ecore.xmi.impl.URIHandlerImpl();

				if (resource.getURI().hasQuery())
					uriHandler.setBaseURI(resource.getURI().trimSegments(1).appendSegment("-1"));
				else
					uriHandler.setBaseURI(resource.getURI());

				// If the URI contains a query string, use it to locate a collection of objects from
				// MongoDB, otherwise simply get the object from MongoDB using the id.

				String query = uri.query();
				DBCollection collection = getCollection(uri);

				if (query != null)
				{
					System.err.println(URI.decode(query)); // TODO : remove debugging printout
					Result result = QueryFactory.eINSTANCE.createResult();
					EList<EObject> values = result.getValues();

					for (DBObject dbObject : collection.find(buildDBObjectQuery(collection, new ExpressionBuilder(URI.decode(query)).parseExpression())))
						values.add(buildEObject(collection, dbObject, resource, uriHandler, true));

					resource.getContents().add(result);
				}
				else
				{
					DBObject dbObject = collection.findOne(new BasicDBObject(ID_KEY, getID(uri)));

					if (dbObject != null)
					{
						EObject eObject = buildEObject(collection, dbObject, resource, uriHandler);

						if (eObject != null)
							resource.getContents().add(eObject);

						Map<Object, Object> response = getResponse(options);
						response.put(URIConverter.RESPONSE_TIME_STAMP_PROPERTY, dbObject.get(TIME_STAMP_KEY));
					}
				}
			}
		};
	}

	public abstract static class MongoDBInputStream extends InputStream implements URIConverter.Loadable
	{
		@Override
		public int read() throws IOException
		{
			return 0;
		}
	}

	private ObjectId getID(URI uri) throws IOException
	{
		// Require that the URI path has the form /database/collection/{id} making the id segment # 2.

		if (uri.segmentCount() != 3)
			throw new IOException("The URI is not of the form 'mongo:/database/collection/{id}");

		String id = uri.segment(2);

		try
		{
			return id.isEmpty() ? null : new ObjectId(uri.segment(2));
		}
		catch (Throwable t)
		{
			return null;
		}
	}

	private DBCollection getCollection(URI uri) throws UnknownHostException, IOException
	{
		// We assume that the URI path has the form /database/collection/{id} making the
		// collection segment # 1.

		if (uri.segmentCount() != 3)
			throw new IOException("The URI is not of the form 'mongo:/database/collection/{id}");

		if (mongoDB == null)
			throw new IOException("MongoDB service is unavailable");

		String port = uri.port();
		MongoURI mongoURI = new MongoURI("mongodb://" + uri.host() + (port != null ? ":" + port : ""));
		Mongo mongo = mongoDB.getMongo(mongoURI);

		if (mongo == null)
			throw new IOException("Could not find MongoDB host: " + mongoURI);

		DB db = mongo.getDB(uri.segment(0));

		if (db == null)
			throw new IOException("Could not find Mongo database: " + uri.segment(0));

		DBCollection collection = db.getCollection(uri.segment(1));

		if (collection == null)
			throw new IOException("Could not find collection: " + uri.segment(1));

		return collection;
	}

	private DBObject buildDBObjectQuery(final DBCollection dbCollection, Expression expression)
	{
		// This function builds a DBObject to be used as a query to MongoDB from the EMF Expression

		final DBObject dbObject = new BasicDBObject();

		if (expression != null)
		{
			new QuerySwitch<Object>()
			{
				@Override
				public Object caseBinaryOperation(BinaryOperation binaryOperation)
				{
					Expression leftOperand = binaryOperation.getLeftOperand();
					String operator = binaryOperation.getOperator();

					if ("==".equals(operator))
					{
						Expression rightOperand = binaryOperation.getRightOperand();
						String property = ExpressionBuilder.toString(leftOperand);

						if (ID_KEY.equals(property))
						{
							dbObject.put(property, new ObjectId(((Literal) rightOperand).getLiteralValue()));
						}
						else if (rightOperand instanceof Literal)
						{
							dbObject.put(property, ((Literal) rightOperand).getLiteralValue());
						}
						else if ("null".equals(ExpressionBuilder.toString(rightOperand)))
						{
							DBObject notExists = new BasicDBObject();
							notExists.put("$exists", Boolean.FALSE);
							dbObject.put(property, notExists);
						}
						else
						{
							// TODO: What to do?
						}
					}
					else if ("!=".equals(operator))
					{
						Expression rightOperand = binaryOperation.getRightOperand();
						String property = ExpressionBuilder.toString(leftOperand);
						if (rightOperand instanceof Literal)
						{
							DBObject notEqual = new BasicDBObject();
							notEqual.put("$ne", ((Literal) rightOperand).getLiteralValue());
							dbObject.put(property, notEqual);
						}
						else if ("null".equals(ExpressionBuilder.toString(rightOperand)))
						{
							DBObject exists = new BasicDBObject();
							exists.put("$exists", Boolean.TRUE);
							dbObject.put(property, exists);
						}
						else
						{
							// TODO: What to do?
						}
					}
					else if ("||".equals(operator))
					{
						DBObject leftObject = buildDBObjectQuery(dbCollection, leftOperand);
						DBObject rightObject = buildDBObjectQuery(dbCollection, binaryOperation.getRightOperand());
						@SuppressWarnings("unchecked")
						List<Object> or = (List<Object>) leftObject.get("$or");
						if (or != null)
						{
							or.add(rightObject);
							dbObject.putAll(leftObject);
						}
						else
						{
							or = new ArrayList<Object>();
							or.add(leftObject);
							or.add(rightObject);
							dbObject.put("$or", or);
						}
					}
					else if ("&&".equals(operator))
					{
						dbObject.putAll(buildDBObjectQuery(dbCollection, leftOperand));
						DBObject rightObject = buildDBObjectQuery(dbCollection, binaryOperation.getRightOperand());
						for (String field : rightObject.keySet())
						{
							Object rightValue = rightObject.get(field);
							Object leftValue = dbObject.get(field);
							if (leftValue instanceof DBObject)
							{
								DBObject leftDBObject = (DBObject) leftValue;
								if (rightValue instanceof DBObject)
								{
									DBObject rightDBObject = (DBObject) rightValue;
									if (leftDBObject.containsField("$nin") && rightDBObject.containsField("$ne"))
									{
										@SuppressWarnings("unchecked")
										List<Object> values = (List<Object>) leftDBObject.get("$nin");
										values.add(rightDBObject.get("$ne"));
									}
									else if (leftDBObject.containsField("$ne") && rightDBObject.containsField("$ne"))
									{
										DBObject nin = new BasicDBObject();
										List<Object> values = new ArrayList<Object>();
										values.add(leftDBObject.get("$ne"));
										values.add(rightDBObject.get("$ne"));
										nin.put("$nin", values);
										dbObject.put(field, nin);
									}
									else
									{
										leftDBObject.putAll(rightDBObject);
									}
								}
								else
								{
									Object all = leftDBObject.get("$all");
									if (all instanceof List<?>)
									{
										@SuppressWarnings("unchecked")
										List<Object> allList = (List<Object>) all;
										allList.add(rightValue);
									}
									else
									{
										// What to do?
									}
								}
							}
							else if (leftValue instanceof List<?>)
							{
								@SuppressWarnings("unchecked")
								List<Object> leftListValue = (List<Object>) leftValue;
								if (rightValue instanceof List<?>)
								{
									leftListValue.addAll((List<?>) rightValue);
								}
								else
								{
									leftListValue.add(rightValue);
								}
							}
							else if (leftValue != null)
							{
								if (rightValue instanceof List<?>)
								{
									@SuppressWarnings("unchecked")
									List<Object> rightListValue = (List<Object>) rightValue;
									rightListValue.add(0, leftValue);
									dbObject.put(field, rightListValue);
								}
								else
								{
									List<Object> listValue = new ArrayList<Object>();
									listValue.add(leftValue);
									listValue.add(rightValue);
									DBObject in = new BasicDBObject("$all", listValue);
									dbObject.put(field, in);
								}
							}
							else
							{
								dbObject.put(field, rightValue);
							}
						}
					}

					return null;
				}
			}.doSwitch(expression);
		}

		return dbObject;
	}

	private DBObject buildDBObject(DB db, EObject eObject, XMLResource.URIHandler uriHandler) throws UnknownHostException, IOException
	{
		// Build a MongoDB object from the EMF object.

		BasicDBObject dbObject = new BasicDBObject();
		EClass eClass = eObject.eClass();

		// We have to add the package namespace URI and eclass name to the object so that we can
		// reconstruct the EMF object when we read it back out of MongoDB.

		dbObject.put(ECLASS_KEY, EcoreUtil.getURI(eClass).toString());

		// Save the XML extrinsic id if necessary

		Resource resource = eObject.eResource();

		if (resource instanceof XMLResource)
		{
			String id = ((XMLResource) resource).getID(eObject);

			if (id != null)
				dbObject.put(EXTRINSIC_ID_KEY, id);
		}

		// All attributes are mapped as key / value pairs with the key being the attribute name.

		for (EAttribute attribute : eClass.getEAllAttributes())
		{
			if (!attribute.isTransient() && !(attribute.isID() && attribute.isDerived()))
			{
				Object value = null;

				if (FeatureMapUtil.isFeatureMap(attribute))
				{
					FeatureMap.Internal featureMap = (Internal) eObject.eGet(attribute);
					Iterator<Entry> iterator = featureMap.iterator();
					ArrayList<DBObject> dbFeatureMap = new ArrayList<DBObject>();

					while (iterator.hasNext())
					{
						DBObject dbEntry = new BasicDBObject();
						Entry entry = iterator.next();
						EStructuralFeature feature = entry.getEStructuralFeature();
						dbEntry.put("key", EcoreUtil.getURI(feature).toString());

						if (feature instanceof EAttribute)
							dbEntry.put("value", getDBAttributeValue((EAttribute) feature, entry.getValue()));
						else
							dbEntry.put("value", buildDBReference(db, (EReference) feature, (EObject) entry.getValue(), uriHandler));

						dbFeatureMap.add(dbEntry);
					}

					value = dbFeatureMap;
				}
				else if (attribute.isMany() && !nativeTypes.contains(attribute.getEAttributeType()))
				{
					@SuppressWarnings("unchecked")
					EList<Object> rawValues = (EList<Object>) eObject.eGet(attribute);
					ArrayList<String> values = new ArrayList<String>(rawValues.size());

					for (Object rawValue : rawValues)
						values.add(EcoreUtil.convertToString(attribute.getEAttributeType(), rawValue));

					value = values;
				}
				else
					value = getDBAttributeValue(attribute, eObject.eGet(attribute));

				dbObject.put(attribute.getName(), value);
			}
		}

		// All references are mapped as key / value pairs with the key being the reference name.

		for (EReference reference : eClass.getEAllReferences())
		{
			if (reference.isTransient())
				continue;

			Object value = null;

			if (reference.isMany())
			{
				// One to many reference

				@SuppressWarnings("unchecked")
				EList<EObject> targetObjects = (EList<EObject>) eObject.eGet(reference);
				ArrayList<Object> dbReferences = new ArrayList<Object>(targetObjects.size());

				for (EObject targetObject : targetObjects)
					dbReferences.add(buildDBReference(db, reference, targetObject, uriHandler));

				value = dbReferences;
			}
			else
			{
				// One to one reference

				EObject targetObject = (EObject) eObject.eGet(reference);

				if (targetObject == null)
					continue;

				value = buildDBReference(db, reference, targetObject, uriHandler);
			}

			dbObject.put(reference.getName(), value);
		}

		return dbObject;
	}

	private Object buildDBReference(DB db, EReference eReference, EObject targetObject, XMLResource.URIHandler uriHandler) throws UnknownHostException, IOException
	{
		InternalEObject internalEObject = (InternalEObject) targetObject;

		if (!eReference.isContainment() || (eReference.isResolveProxies() && internalEObject.eDirectResource() != null))
		{
			// Cross-document containment, or non-containment reference - build a proxy

			BasicDBObject dbObject = new BasicDBObject(2);
			dbObject.put(PROXY_KEY, uriHandler.deresolve(EcoreUtil.getURI(targetObject)).toString());
			dbObject.put(ECLASS_KEY, EcoreUtil.getURI(targetObject.eClass()).toString());
			return dbObject;
		}
		else
		{
			// Non cross-document containment reference - build a MongoDB embedded object

			return buildDBObject(db, targetObject, uriHandler);
		}
	}

	private EObject buildEObject(DBCollection collection, DBObject dbObject, Resource resource, XMLResource.URIHandler uriHandler)
	{
		return buildEObject(collection, dbObject, resource, uriHandler, false);
	}

	private EObject buildEObject(DBCollection collection, DBObject dbObject, Resource resource, XMLResource.URIHandler uriHandler, boolean isProxy)
	{
		ResourceSet resourceSet = resource.getResourceSet();

		// Build an EMF object from the MongodDB object

		EObject eObject = createEObject(dbObject, resource.getResourceSet());

		if (eObject != null)
		{
			if (isProxy)
			{
				URI proxyURI = URI.createURI("../" + collection.getName() + "/" + dbObject.get(ID_KEY) + "#/0");
				((InternalEObject) eObject).eSetProxyURI(uriHandler.resolve(proxyURI));
			}

			// Load the XML extrinsic id if necessary

			String id = (String) dbObject.get(EXTRINSIC_ID_KEY);

			if (id != null && resource instanceof XMLResource)
				((XMLResource) resource).setID(eObject, id);

			// All features are mapped as key / value pairs with the key being the attribute name.

			for (EStructuralFeature feature : eObject.eClass().getEAllStructuralFeatures())
			{
				if (feature instanceof EAttribute)
				{
					EAttribute attribute = (EAttribute) feature;

					if (!isProxy || !FeatureMapUtil.isFeatureMap(attribute))
						buildEObjectAttribute(collection, dbObject, resource, uriHandler, resourceSet, eObject, attribute);
				}
				else if (!isProxy)
					buildEObjectReference(collection, dbObject, resource, uriHandler, eObject, (EReference) feature);
			}
		}
		return eObject;
	}

	/**
	 * @param collection
	 * @param dbObject
	 * @param resource
	 * @param uriHandler
	 * @param resourceSet
	 * @param eObject
	 * @param attribute
	 */
	@SuppressWarnings("unchecked")
	private void buildEObjectAttribute(DBCollection collection, DBObject dbObject, Resource resource, XMLResource.URIHandler uriHandler, ResourceSet resourceSet, EObject eObject, EAttribute attribute)
	{
		if (!attribute.isTransient() && !(attribute.isID() && attribute.isDerived()) && dbObject.containsField(attribute.getName()))
		{
			Object value = dbObject.get(attribute.getName());

			if (FeatureMapUtil.isFeatureMap(attribute))
			{
				FeatureMap.Internal featureMap = (Internal) eObject.eGet(attribute);

				for (DBObject entry : (ArrayList<DBObject>) value)
				{
					EStructuralFeature feature = (EStructuralFeature) resourceSet.getEObject(URI.createURI((String) entry.get("key")), true);

					if (feature instanceof EAttribute)
						featureMap.add(feature, getEObjectAttributeValue((EAttribute) feature, entry.get("value")));
					else
					{
						EReference reference = (EReference) feature;
						EObject target = buildEObjectReference(collection, entry.get("value"), resource, uriHandler, reference.isResolveProxies());

						if (target != null)
							featureMap.add(feature, target);
					}
				}
			}
			else if (attribute.isMany() && !nativeTypes.contains(attribute.getEAttributeType()))
			{
				ArrayList<Object> values = new ArrayList<Object>();
				ArrayList<String> rawValues = (ArrayList<String>) value;

				for (String rawValue : rawValues)
					values.add(EcoreUtil.createFromString(attribute.getEAttributeType(), rawValue));

				eObject.eSet(attribute, values);
			}
			else
				eObject.eSet(attribute, getEObjectAttributeValue(attribute, value));
		}
	}

	/**
	 * @param collection
	 * @param dbObject
	 * @param resource
	 * @param uriHandler
	 * @param eObject
	 * @param reference
	 */
	private void buildEObjectReference(DBCollection collection, DBObject dbObject, Resource resource, XMLResource.URIHandler uriHandler, EObject eObject, EReference reference)
	{
		if (reference.isTransient())
			return;

		String field = reference.getName();

		if (dbObject.containsField(field))
		{
			if (reference.isMany())
			{
				@SuppressWarnings("unchecked")
				List<Object> dbReferences = (List<Object>) dbObject.get(field);

				@SuppressWarnings("unchecked")
				EList<EObject> eObjects = (EList<EObject>) eObject.eGet(reference);

				for (Object dbReference : dbReferences)
				{
					EObject target = buildEObjectReference(collection, dbReference, resource, uriHandler, reference.isResolveProxies());
					eObjects.add(target);
				}
			}
			else
			{
				EObject target = buildEObjectReference(collection, dbObject.get(field), resource, uriHandler, reference.isResolveProxies());
				eObject.eSet(reference, target);
			}
		}
	}

	private EObject buildEObjectReference(DBCollection collection, Object dbReference, Resource resource, XMLResource.URIHandler uriHandler, boolean referenceResolvesProxies)
	{
		if (dbReference == null)
			return null;

		// Build an EMF reference from the data in MongoDB.

		DBObject dbObject = (DBObject) dbReference;
		String proxy = (String) dbObject.get(PROXY_KEY);

		if (proxy == null)
			return buildEObject(collection, dbObject, resource, uriHandler);
		else
			return buildProxy(dbObject, resource.getResourceSet(), uriHandler, referenceResolvesProxies);
	}

	private EObject buildProxy(DBObject dbObject, ResourceSet resourceSet, XMLResource.URIHandler uriHandler, boolean referenceResolvedProxies)
	{
		EObject eObject = null;
		URI proxyURI = uriHandler.resolve(URI.createURI((String) dbObject.get(PROXY_KEY)));

		if (!referenceResolvedProxies)
			eObject = resourceSet.getEObject(proxyURI, true);
		else
		{
			eObject = createEObject(dbObject, resourceSet);

			if (eObject != null)
				((InternalEObject) eObject).eSetProxyURI(proxyURI);
		}

		return eObject;
	}

	private EObject createEObject(DBObject dbObject, ResourceSet resourceSet)
	{
		if (dbObject == null)
			return null;

		EClass eClass = (EClass) resourceSet.getEObject(URI.createURI((String) dbObject.get(ECLASS_KEY)), true);
		return EcoreUtil.create(eClass);
	}

	private Object getDBAttributeValue(EAttribute attribute, Object rawValue)
	{
		if (!nativeTypes.contains(attribute.getEAttributeType()))
			return EcoreUtil.convertToString(attribute.getEAttributeType(), rawValue);

		return rawValue;
	}

	/**
	 * @param attribute
	 * @param value
	 * @return
	 */
	private Object getEObjectAttributeValue(EAttribute attribute, Object value)
	{
		if (!nativeTypes.contains(attribute.getEAttributeType()))
			value = EcoreUtil.createFromString(attribute.getEAttributeType(), (String) value);
		else if (EcorePackage.Literals.EBYTE.equals(attribute.getEAttributeType()) || EcorePackage.Literals.EBYTE_OBJECT.equals(attribute.getEAttributeType()))
			value = ((Integer) value).byteValue();
		else if (EcorePackage.Literals.EFLOAT.equals(attribute.getEAttributeType()) || EcorePackage.Literals.EFLOAT_OBJECT.equals(attribute.getEAttributeType()))
			value = ((Double) value).floatValue();

		return value;
	}

	static final String TIME_STAMP_KEY = "_timeStamp";
	static final String ID_KEY = "_id";
	static final String ECLASS_KEY = "_eClass";
	static final String PROXY_KEY = "_eProxyURI";
	static final String EXTRINSIC_ID_KEY = "_eId";

	private static HashSet<EDataType> nativeTypes = new HashSet<EDataType>();

	private IMongoDB mongoDB;

	// These are the EMF types natively supported by MongoDB. All other types are stored as a string.

	static
	{
		nativeTypes.add(EcorePackage.Literals.EBOOLEAN);
		nativeTypes.add(EcorePackage.Literals.EBOOLEAN_OBJECT);
		nativeTypes.add(EcorePackage.Literals.EBYTE);
		nativeTypes.add(EcorePackage.Literals.EBYTE_OBJECT);
		nativeTypes.add(EcorePackage.Literals.EBYTE_ARRAY);
		nativeTypes.add(EcorePackage.Literals.EINT);
		nativeTypes.add(EcorePackage.Literals.EINTEGER_OBJECT);
		nativeTypes.add(EcorePackage.Literals.ELONG);
		nativeTypes.add(EcorePackage.Literals.ELONG_OBJECT);
		nativeTypes.add(EcorePackage.Literals.EDOUBLE);
		nativeTypes.add(EcorePackage.Literals.EDOUBLE_OBJECT);
		nativeTypes.add(EcorePackage.Literals.EFLOAT);
		nativeTypes.add(EcorePackage.Literals.EFLOAT_OBJECT);
		nativeTypes.add(EcorePackage.Literals.EDATE);
		nativeTypes.add(EcorePackage.Literals.ESTRING);
	}
}
