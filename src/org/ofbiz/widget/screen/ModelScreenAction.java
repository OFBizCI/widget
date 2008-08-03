/*******************************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *******************************************************************************/
package org.ofbiz.widget.screen;

import java.io.Serializable;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.PatternSyntaxException;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpSession;

import javolution.util.FastList;
import javolution.util.FastMap;

import org.ofbiz.base.util.BshUtil;
import org.ofbiz.base.util.Debug;
import org.ofbiz.base.util.GeneralException;
import org.ofbiz.base.util.GroovyUtil;
import org.ofbiz.base.util.ObjectType;
import org.ofbiz.base.util.StringUtil;
import org.ofbiz.base.util.UtilGenerics;
import org.ofbiz.base.util.UtilProperties;
import org.ofbiz.base.util.UtilValidate;
import org.ofbiz.base.util.UtilXml;
import org.ofbiz.base.util.collections.FlexibleMapAccessor;
import org.ofbiz.base.util.collections.ResourceBundleMapWrapper;
import org.ofbiz.base.util.string.FlexibleStringExpander;
import org.ofbiz.entity.GenericEntityException;
import org.ofbiz.entity.GenericValue;
import org.ofbiz.entity.finder.ByAndFinder;
import org.ofbiz.entity.finder.ByConditionFinder;
import org.ofbiz.entity.finder.EntityFinderUtil;
import org.ofbiz.entity.finder.PrimaryKeyFinder;
import org.ofbiz.minilang.MiniLangException;
import org.ofbiz.minilang.SimpleMethod;
import org.ofbiz.minilang.method.MethodContext;
import org.ofbiz.service.DispatchContext;
import org.ofbiz.service.GenericServiceException;
import org.ofbiz.service.ModelService;
import org.w3c.dom.Element;


/**
 * Widget Library - Screen model class
 */
public abstract class ModelScreenAction implements Serializable {
    public static final String module = ModelScreenAction.class.getName();

    protected ModelScreen modelScreen;

    public ModelScreenAction(ModelScreen modelScreen, Element actionElement) {
        this.modelScreen = modelScreen;
        if (Debug.verboseOn()) Debug.logVerbose("Reading Screen action with name: " + actionElement.getNodeName(), module);
    }
    
    public abstract void runAction(Map<String, Object> context) throws GeneralException;
    
    public static List<ModelScreenAction> readSubActions(ModelScreen modelScreen, Element parentElement) {
        List<ModelScreenAction> actions = FastList.newInstance();
        
        List actionElementList = UtilXml.childElementList(parentElement);
        Iterator actionElementIter = actionElementList.iterator();
        while (actionElementIter.hasNext()) {
            Element actionElement = (Element) actionElementIter.next();
            if ("set".equals(actionElement.getNodeName())) {
                actions.add(new SetField(modelScreen, actionElement));
            } else if ("property-map".equals(actionElement.getNodeName())) {
                actions.add(new PropertyMap(modelScreen, actionElement));
            } else if ("property-to-field".equals(actionElement.getNodeName())) {
                actions.add(new PropertyToField(modelScreen, actionElement));
            } else if ("script".equals(actionElement.getNodeName())) {
                actions.add(new Script(modelScreen, actionElement));
            } else if ("service".equals(actionElement.getNodeName())) {
                actions.add(new Service(modelScreen, actionElement));
            } else if ("entity-one".equals(actionElement.getNodeName())) {
                actions.add(new EntityOne(modelScreen, actionElement));
            } else if ("entity-and".equals(actionElement.getNodeName())) {
                actions.add(new EntityAnd(modelScreen, actionElement));
            } else if ("entity-condition".equals(actionElement.getNodeName())) {
                actions.add(new EntityCondition(modelScreen, actionElement));
            } else if ("get-related-one".equals(actionElement.getNodeName())) {
                actions.add(new GetRelatedOne(modelScreen, actionElement));
            } else if ("get-related".equals(actionElement.getNodeName())) {
                actions.add(new GetRelated(modelScreen, actionElement));
            } else {
                throw new IllegalArgumentException("Action element not supported with name: " + actionElement.getNodeName());
            }
        }
        
        return actions;
    }
    
    public static void runSubActions(List actions, Map<String, Object> context) throws GeneralException {
        if (actions == null) return;
        
        Iterator actionIter = actions.iterator();
        while (actionIter.hasNext()) {
            ModelScreenAction action = (ModelScreenAction) actionIter.next();
            if (Debug.verboseOn()) Debug.logVerbose("Running screen action " + action.getClass().getName(), module);
            action.runAction(context);
        }
    }
    
    @SuppressWarnings("serial")
    public static class SetField extends ModelScreenAction {
        protected FlexibleMapAccessor<Object> field;
        protected FlexibleMapAccessor<Object> fromField;
        protected FlexibleStringExpander valueExdr;
        protected FlexibleStringExpander defaultExdr;
        protected FlexibleStringExpander globalExdr;
        protected String type;
        protected String toScope;
        protected String fromScope;
        
        public SetField(ModelScreen modelScreen, Element setElement) {
            super (modelScreen, setElement);
            this.field = new FlexibleMapAccessor<Object>(setElement.getAttribute("field"));
            this.fromField = new FlexibleMapAccessor<Object>(setElement.getAttribute("from-field"));
            this.valueExdr = new FlexibleStringExpander(setElement.getAttribute("value"));
            this.defaultExdr = new FlexibleStringExpander(setElement.getAttribute("default-value"));
            this.globalExdr = new FlexibleStringExpander(setElement.getAttribute("global"));
            this.type = setElement.getAttribute("type");
            this.toScope = setElement.getAttribute("to-scope");
            this.fromScope = setElement.getAttribute("from-scope");
            if (!this.fromField.isEmpty() && !this.valueExdr.isEmpty()) {
                throw new IllegalArgumentException("Cannot specify a from-field [" + setElement.getAttribute("from-field") + "] and a value [" + setElement.getAttribute("value") + "] on the set action in a screen widget");
            }
        }
        
        public void runAction(Map<String, Object> context) {
            String globalStr = this.globalExdr.expandString(context);
            // default to false
            boolean global = "true".equals(globalStr);
            
            Object newValue = null;
            if (this.fromScope != null && this.fromScope.equals("user")) {
                if (!this.fromField.isEmpty()) {
                    HttpSession session = (HttpSession) context.get("session");
                    newValue = getInMemoryPersistedFromField(session, context);
                    if (Debug.verboseOn()) Debug.logVerbose("In user getting value for field from [" + this.fromField.getOriginalName() + "]: " + newValue, module);
                } else if (!this.valueExdr.isEmpty()) {
                    newValue = this.valueExdr.expandString(context);
                }
            } else if (this.fromScope != null && this.fromScope.equals("application")) {
                if (!this.fromField.isEmpty()) {
                    ServletContext servletContext = (ServletContext) context.get("application");
                    newValue = getInMemoryPersistedFromField(servletContext, context);
                    if (Debug.verboseOn()) Debug.logVerbose("In application getting value for field from [" + this.fromField.getOriginalName() + "]: " + newValue, module);
                } else if (!this.valueExdr.isEmpty()) {
                    newValue = this.valueExdr.expandString(context);
                }
            } else {
                if (!this.fromField.isEmpty()) {
                    newValue = this.fromField.get(context);
                    if (Debug.verboseOn()) Debug.logVerbose("In screen getting value for field from [" + this.fromField.getOriginalName() + "]: " + newValue, module);
                } else if (!this.valueExdr.isEmpty()) {
                    newValue = this.valueExdr.expandString(context);
                }
            }

            // If newValue is still empty, use the default value
            if (ObjectType.isEmpty(newValue) && !this.defaultExdr.isEmpty()) {
                newValue = this.defaultExdr.expandString(context);
            }
            
            if (UtilValidate.isNotEmpty(this.type)) {
                try {
                    newValue = ObjectType.simpleTypeConvert(newValue, this.type, null, (TimeZone) context.get("timeZone"), (Locale) context.get("locale"), true);
                } catch (GeneralException e) {
                    String errMsg = "Could not convert field value for the field: [" + this.field.getOriginalName() + "] to the [" + this.type + "] type for the value [" + newValue + "]: " + e.toString();
                    Debug.logError(e, errMsg, module);
                    throw new IllegalArgumentException(errMsg);
                }
            }
            
            if (this.toScope != null && this.toScope.equals("user")) {
                String originalName = this.field.getOriginalName();
                List<String> currentWidgetTrail = UtilGenerics.toList(context.get("_WIDGETTRAIL_"));
                String newKey = "";
                if (currentWidgetTrail != null) {
                    newKey = StringUtil.join(currentWidgetTrail, "|");
                }
                if (UtilValidate.isNotEmpty(newKey)) {
                    newKey += "|";
                }
                newKey += originalName;
                HttpSession session = (HttpSession)context.get("session");
                session.setAttribute(newKey, newValue);
                if (Debug.verboseOn()) Debug.logVerbose("In user setting value for field from [" + this.field.getOriginalName() + "]: " + newValue, module);
            } else if (this.toScope != null && this.toScope.equals("application")) {
                String originalName = this.field.getOriginalName();
                List<String> currentWidgetTrail = UtilGenerics.toList(context.get("_WIDGETTRAIL_"));
                String newKey = "";
                if (currentWidgetTrail != null) {
                    newKey = StringUtil.join(currentWidgetTrail, "|");
                }
                if (UtilValidate.isNotEmpty(newKey)) {
                    newKey += "|";
                }
                newKey += originalName;
                ServletContext servletContext = (ServletContext)context.get("application");
                servletContext.setAttribute(newKey, newValue);
                if (Debug.verboseOn()) Debug.logVerbose("In application setting value for field from [" + this.field.getOriginalName() + "]: " + newValue, module);
            } else {
                // only do this if it is not global, if global ONLY put it in the global context
                if (!global) {
                    if (Debug.verboseOn()) Debug.logVerbose("In screen setting field [" + this.field.getOriginalName() + "] to value: " + newValue, module);
                    this.field.put(context, newValue);
                }
            }
            
            if (global) {
                Map<String, Object> globalCtx = UtilGenerics.checkMap(context.get("globalContext"));
                if (globalCtx != null) {
                    this.field.put(globalCtx, newValue);
                } else {
                    this.field.put(context, newValue);
                }
            }
            
            // this is a hack for backward compatibility with the JPublish page object
            Map<String, Object> page = UtilGenerics.checkMap(context.get("page"));
            if (page != null) {
                this.field.put(page, newValue);
            }
        }
        
        public Object getInMemoryPersistedFromField(Object storeAgent, Map<String, Object> context) {
            Object newValue = null;
            String originalName = this.fromField.getOriginalName();
            List<String> currentWidgetTrail = UtilGenerics.toList(context.get("_WIDGETTRAIL_"));
            List<String> trailList = new ArrayList<String>();
            if (currentWidgetTrail != null) {
                trailList.addAll(currentWidgetTrail);
            }
            
            for (int i=trailList.size(); i >= 0; i--) {
                List<String> subTrail = trailList.subList(0,i);
                String newKey = null;
                if (subTrail.size() > 0)
                    newKey = StringUtil.join(subTrail, "|") + "|" + originalName;
                else
                    newKey = originalName;
                
                if (storeAgent instanceof ServletContext) {
                    newValue = ((ServletContext)storeAgent).getAttribute(newKey);
                } else if (storeAgent instanceof HttpSession) {
                    newValue = ((HttpSession)storeAgent).getAttribute(newKey);
                }
                if (newValue != null) {
                    break;
                }
            }
            return newValue;
        }
    }
    
    @SuppressWarnings("serial")
    public static class PropertyMap extends ModelScreenAction {
        protected FlexibleStringExpander resourceExdr;
        protected FlexibleMapAccessor<ResourceBundleMapWrapper> mapNameAcsr;
        protected FlexibleStringExpander globalExdr;
        
        public PropertyMap(ModelScreen modelScreen, Element setElement) {
            super (modelScreen, setElement);
            this.resourceExdr = new FlexibleStringExpander(setElement.getAttribute("resource"));
            this.mapNameAcsr = new FlexibleMapAccessor<ResourceBundleMapWrapper>(setElement.getAttribute("map-name"));
            this.globalExdr = new FlexibleStringExpander(setElement.getAttribute("global"));
        }
        
        public void runAction(Map<String, Object> context) {
            String globalStr = this.globalExdr.expandString(context);
            // default to false
            boolean global = "true".equals(globalStr);

            Locale locale = (Locale) context.get("locale");
            String resource = this.resourceExdr.expandString(context, locale);
            
            ResourceBundleMapWrapper existingPropMap = this.mapNameAcsr.get(context);
            if (existingPropMap == null) {
                this.mapNameAcsr.put(context, UtilProperties.getResourceBundleMap(resource, locale, context));
            } else {
                existingPropMap.addBottomResourceBundle(resource);
            }

            if (global) {
                Map<String, Object> globalCtx = UtilGenerics.checkMap(context.get("globalContext"));
                if (globalCtx != null) {
                    ResourceBundleMapWrapper globalExistingPropMap = this.mapNameAcsr.get(globalCtx);
                    if (globalExistingPropMap == null) {
                        this.mapNameAcsr.put(globalCtx, UtilProperties.getResourceBundleMap(resource, locale, context));
                    } else {
                        // is it the same object? if not add it in here too...
                        if (existingPropMap != globalExistingPropMap) {
                            globalExistingPropMap.addBottomResourceBundle(resource);
                        }
                    }
                }
            }
        }
    }
    
    @SuppressWarnings("serial")
    public static class PropertyToField extends ModelScreenAction {
        
        protected FlexibleStringExpander resourceExdr;
        protected FlexibleStringExpander propertyExdr;
        protected FlexibleMapAccessor<Object> fieldAcsr;
        protected FlexibleStringExpander defaultExdr;
        protected boolean noLocale;
        protected FlexibleMapAccessor<List<? extends Object>> argListAcsr;
        protected FlexibleStringExpander globalExdr;

        public PropertyToField(ModelScreen modelScreen, Element setElement) {
            super (modelScreen, setElement);
            this.resourceExdr = new FlexibleStringExpander(setElement.getAttribute("resource"));
            this.propertyExdr = new FlexibleStringExpander(setElement.getAttribute("property"));
            this.fieldAcsr = new FlexibleMapAccessor<Object>(setElement.getAttribute("field"));
            this.defaultExdr = new FlexibleStringExpander(setElement.getAttribute("default"));
            noLocale = "true".equals(setElement.getAttribute("no-locale"));
            this.argListAcsr = new FlexibleMapAccessor<List<? extends Object>>(setElement.getAttribute("arg-list-name"));
            this.globalExdr = new FlexibleStringExpander(setElement.getAttribute("global"));
        }
        
        public void runAction(Map<String, Object> context) {
            //String globalStr = this.globalExdr.expandString(context);
            // default to false
            //boolean global = "true".equals(globalStr);

            Locale locale = (Locale) context.get("locale");
            String resource = this.resourceExdr.expandString(context, locale);
            String property = this.propertyExdr.expandString(context, locale);
            
            String value = null;
            if (noLocale) {
                value = UtilProperties.getPropertyValue(resource, property);
            } else {
                value = UtilProperties.getMessage(resource, property, locale);
            }
            if (value == null || value.length() == 0) {
                value = this.defaultExdr.expandString(context);
            }
            
            // note that expanding the value string here will handle defaultValue and the string from 
            //  the properties file; if we decide later that we don't want the string from the properties 
            //  file to be expanded we should just expand the defaultValue at the beginning of this method.
            value = FlexibleStringExpander.expandString(value, context);

            if (!argListAcsr.isEmpty()) {
                List<? extends Object> argList = argListAcsr.get(context);
                if (argList != null && argList.size() > 0) {
                    value = MessageFormat.format(value, argList.toArray());
                }
            }

            fieldAcsr.put(context, value);
        }
    }
    
    @SuppressWarnings("serial")
    public static class Script extends ModelScreenAction {
        protected String location;
        
        public Script(ModelScreen modelScreen, Element scriptElement) {
            super (modelScreen, scriptElement);
            this.location = scriptElement.getAttribute("location");
        }
        
        public void runAction(Map<String, Object> context) throws GeneralException {
            if (location.endsWith(".bsh")) {
                try {
                    BshUtil.runBshAtLocation(location, context);
                } catch (GeneralException e) {
                    throw new GeneralException("Error running BSH script at location [" + location + "]", e);
                }
            } else if (location.endsWith(".groovy")) {
                try {
                    GroovyUtil.runScriptAtLocation(location, context);
                } catch (GeneralException e) {
                    throw new GeneralException("Error running Groovy script at location [" + location + "]", e);
                }
            } else if (location.contains(".xml#")) {
                String xmlResource = ScreenFactory.getResourceNameFromCombined(location);
                String methodName = ScreenFactory.getScreenNameFromCombined(location);
                Map<String, Object> localContext = FastMap.newInstance();
                localContext.putAll(context);
                DispatchContext ctx = this.modelScreen.getDispatcher(context).getDispatchContext();
                MethodContext methodContext = new MethodContext(ctx, localContext, null);
                try {
                    SimpleMethod.runSimpleMethod(xmlResource, methodName, methodContext);
                    context.putAll(methodContext.getResults());
                } catch (MiniLangException e) {
                    throw new GeneralException("Error running simple method at location [" + location + "]", e);
                }
            } else {
                throw new GeneralException("For screen script actions the script type is not yet supported for location: [" + location + "]");
            }
        }
    }

    @SuppressWarnings("serial")
    public static class Service extends ModelScreenAction {
        protected FlexibleStringExpander serviceNameExdr;
        protected FlexibleMapAccessor<Map<String, Object>> resultMapNameAcsr;
        protected FlexibleStringExpander autoFieldMapExdr;
        protected Map<FlexibleMapAccessor<Object>, Object> fieldMap;
        
        public Service(ModelScreen modelScreen, Element serviceElement) {
            super (modelScreen, serviceElement);
            this.serviceNameExdr = new FlexibleStringExpander(serviceElement.getAttribute("service-name"));
            this.resultMapNameAcsr = UtilValidate.isNotEmpty(serviceElement.getAttribute("result-map-name")) ? new FlexibleMapAccessor<Map<String, Object>>(serviceElement.getAttribute("result-map-name")) : null;
            this.autoFieldMapExdr = new FlexibleStringExpander(serviceElement.getAttribute("auto-field-map"));
            this.fieldMap = EntityFinderUtil.makeFieldMap(serviceElement);
        }
        
        public void runAction(Map<String, Object> context) {
            String serviceNameExpanded = this.serviceNameExdr.expandString(context);
            if (UtilValidate.isEmpty(serviceNameExpanded)) {
                throw new IllegalArgumentException("Service name was empty, expanded from: " + this.serviceNameExdr.getOriginal());
            }
            
            String autoFieldMapString = this.autoFieldMapExdr.expandString(context);
            
            try {
                Map<String, Object> serviceContext = null;
                if ("true".equals(autoFieldMapString)) {
                    DispatchContext dc = this.modelScreen.getDispatcher(context).getDispatchContext();
                    // try a map called "parameters", try it first so values from here are overriden by values in the main context
                    Map<String, Object> combinedMap = FastMap.newInstance();
                    Map<String, Object> parametersObj = UtilGenerics.toMap(context.get("parameters"));
                    if (parametersObj != null) {
                        combinedMap.putAll(parametersObj);
                    }
                    combinedMap.putAll(context);
                    serviceContext = dc.makeValidContext(serviceNameExpanded, ModelService.IN_PARAM, combinedMap);
                } else if (UtilValidate.isNotEmpty(autoFieldMapString) && !"false".equals(autoFieldMapString)) {
                    FlexibleMapAccessor<Object> fieldFma = new FlexibleMapAccessor<Object>(autoFieldMapString);
                    Map<String, Object> autoFieldMap = UtilGenerics.toMap(fieldFma.get(context));
                    if (autoFieldMap != null) {
                        serviceContext = this.modelScreen.getDispatcher(context).getDispatchContext().makeValidContext(serviceNameExpanded, ModelService.IN_PARAM, autoFieldMap);
                    }
                } 
                if (serviceContext == null) {
                    serviceContext = FastMap.newInstance();
                }
                
                if (this.fieldMap != null) {
                    EntityFinderUtil.expandFieldMapToContext(this.fieldMap, context, serviceContext);
                }
                
                Map<String, Object> result = this.modelScreen.getDispatcher(context).runSync(serviceNameExpanded, serviceContext);
                
                if (this.resultMapNameAcsr != null) {
                    this.resultMapNameAcsr.put(context, result);
                    String queryString = (String)result.get("queryString");
                    context.put("queryString", queryString);
                    context.put("queryStringMap", result.get("queryStringMap"));
                    if (UtilValidate.isNotEmpty(queryString)){
                        try {
                            String queryStringEncoded = queryString.replaceAll("&", "%26");
                            context.put("queryStringEncoded", queryStringEncoded);
                        } catch (PatternSyntaxException e) {
                            
                        }
                    }
                } else {
                    context.putAll(result);
                }
            } catch (GenericServiceException e) {
                String errMsg = "Error calling service with name " + serviceNameExpanded + ": " + e.toString();
                Debug.logError(e, errMsg, module);
                throw new IllegalArgumentException(errMsg);
            }
        }
    }

    @SuppressWarnings("serial")
    public static class EntityOne extends ModelScreenAction {
        protected PrimaryKeyFinder finder;
        
        public EntityOne(ModelScreen modelScreen, Element entityOneElement) {
            super (modelScreen, entityOneElement);
            finder = new PrimaryKeyFinder(entityOneElement);
        }
        
        public void runAction(Map<String, Object> context) {
            try {
                finder.runFind(context, this.modelScreen.getDelegator(context));
            } catch (GeneralException e) {
                String errMsg = "Error doing entity query by condition: " + e.toString();
                Debug.logError(e, errMsg, module);
                throw new IllegalArgumentException(errMsg);
            }
        }
    }

    @SuppressWarnings("serial")
    public static class EntityAnd extends ModelScreenAction {
        protected ByAndFinder finder;
        
        public EntityAnd(ModelScreen modelScreen, Element entityAndElement) {
            super (modelScreen, entityAndElement);
            finder = new ByAndFinder(entityAndElement);
        }
        
        public void runAction(Map<String, Object> context) {
            try {
                finder.runFind(context, this.modelScreen.getDelegator(context));
            } catch (GeneralException e) {
                String errMsg = "Error doing entity query by condition: " + e.toString();
                Debug.logError(e, errMsg, module);
                throw new IllegalArgumentException(errMsg);
            }
        }
    }

    @SuppressWarnings("serial")
    public static class EntityCondition extends ModelScreenAction {
        ByConditionFinder finder;
        
        public EntityCondition(ModelScreen modelScreen, Element entityConditionElement) {
            super (modelScreen, entityConditionElement);
            finder = new ByConditionFinder(entityConditionElement);
        }
        
        public void runAction(Map<String, Object> context) {
            try {
                finder.runFind(context, this.modelScreen.getDelegator(context));
            } catch (GeneralException e) {
                String errMsg = "Error doing entity query by condition: " + e.toString();
                Debug.logError(e, errMsg, module);
                throw new IllegalArgumentException(errMsg);
            }
        }
    }

    @SuppressWarnings("serial")
    public static class GetRelatedOne extends ModelScreenAction {
        protected FlexibleMapAccessor<Object> valueNameAcsr;
        protected FlexibleMapAccessor<Object> toValueNameAcsr;
        protected String relationName;
        protected boolean useCache;
        
        public GetRelatedOne(ModelScreen modelScreen, Element getRelatedOneElement) {
            super (modelScreen, getRelatedOneElement);
            this.valueNameAcsr = new FlexibleMapAccessor<Object>(getRelatedOneElement.getAttribute("value-name"));
            this.toValueNameAcsr = new FlexibleMapAccessor<Object>(getRelatedOneElement.getAttribute("to-value-name"));
            this.relationName = getRelatedOneElement.getAttribute("relation-name");
            this.useCache = "true".equals(getRelatedOneElement.getAttribute("use-cache"));
        }

        public void runAction(Map<String, Object> context) {
            Object valueObject = valueNameAcsr.get(context);
            if (valueObject == null) {
                Debug.logVerbose("Value not found with name: " + valueNameAcsr + ", not getting related...", module);
                return;
            }
            if (!(valueObject instanceof GenericValue)) {
                String errMsg = "Env variable for value-name " + valueNameAcsr.toString() + " is not a GenericValue object; for the relation-name: " + relationName + "]";
                Debug.logError(errMsg, module);
                throw new IllegalArgumentException(errMsg);
            }
            GenericValue value = (GenericValue) valueObject;
            try {
                if (useCache) {
                    toValueNameAcsr.put(context, value.getRelatedOneCache(relationName));
                } else {
                    toValueNameAcsr.put(context, value.getRelatedOne(relationName));
                }
            } catch (GenericEntityException e) {
                String errMsg = "Problem getting related one from entity with name " + value.getEntityName() + " for the relation-name: " + relationName + ": " + e.getMessage();
                Debug.logError(e, errMsg, module);
                throw new IllegalArgumentException(errMsg);
            }
        }
        
    }

    @SuppressWarnings("serial")
    public static class GetRelated extends ModelScreenAction {
        protected FlexibleMapAccessor<Object> valueNameAcsr;
        protected FlexibleMapAccessor<List<GenericValue>> listNameAcsr;
        protected FlexibleMapAccessor<Map<String, Object>> mapAcsr;
        protected FlexibleMapAccessor<List<String>> orderByListAcsr;
        protected String relationName;
        protected boolean useCache;
        
        public GetRelated(ModelScreen modelScreen, Element getRelatedElement) {
            super (modelScreen, getRelatedElement);
            this.valueNameAcsr = new FlexibleMapAccessor<Object>(getRelatedElement.getAttribute("value-name"));
            this.listNameAcsr = new FlexibleMapAccessor<List<GenericValue>>(getRelatedElement.getAttribute("list-name"));
            this.relationName = getRelatedElement.getAttribute("relation-name");
            this.mapAcsr = new FlexibleMapAccessor<Map<String, Object>>(getRelatedElement.getAttribute("map-name"));
            this.orderByListAcsr = new FlexibleMapAccessor<List<String>>(getRelatedElement.getAttribute("order-by-list-name"));
            this.useCache = "true".equals(getRelatedElement.getAttribute("use-cache"));
        }

        public void runAction(Map<String, Object> context) {
            Object valueObject = valueNameAcsr.get(context);
            if (valueObject == null) {
                Debug.logVerbose("Value not found with name: " + valueNameAcsr + ", not getting related...", module);
                return;
            }
            if (!(valueObject instanceof GenericValue)) {
                String errMsg = "Env variable for value-name " + valueNameAcsr.toString() + " is not a GenericValue object; for the relation-name: " + relationName + "]";
                Debug.logError(errMsg, module);
                throw new IllegalArgumentException(errMsg);
            }
            GenericValue value = (GenericValue) valueObject;
            List<String> orderByNames = null;
            if (!orderByListAcsr.isEmpty()) {
                orderByNames = orderByListAcsr.get(context);
            }
            Map<String, Object> constraintMap = null;
            if (!mapAcsr.isEmpty()) {
                constraintMap = mapAcsr.get(context);
            }
            try {
                if (useCache) {
                    listNameAcsr.put(context, value.getRelatedCache(relationName, constraintMap, orderByNames));
                } else {
                    listNameAcsr.put(context, value.getRelated(relationName, constraintMap, orderByNames));
                }
            } catch (GenericEntityException e) {
                String errMsg = "Problem getting related from entity with name " + value.getEntityName() + " for the relation-name: " + relationName + ": " + e.getMessage();
                Debug.logError(e, errMsg, module);
                throw new IllegalArgumentException(errMsg);
            }
        }
    }

}
