/**
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.openejb.client;

import javax.xml.ws.spi.Provider;
import javax.xml.ws.spi.ServiceDelegate;
import javax.xml.ws.Endpoint;
import javax.xml.ws.BindingProvider;
import javax.xml.ws.Dispatch;
import javax.xml.ws.Service;
import javax.xml.ws.WebServiceException;
import javax.xml.ws.soap.SOAPBinding;
import javax.xml.ws.handler.HandlerResolver;
import javax.xml.namespace.QName;
import javax.xml.bind.JAXBContext;
import java.net.URL;
import java.util.Iterator;
import java.util.Map;
import java.util.List;
import java.util.Properties;
import java.util.Collections;
import java.util.Enumeration;
import java.util.ArrayList;
import java.util.logging.Logger;
import java.util.logging.Level;
import java.util.concurrent.Executor;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.OutputStream;
import java.io.FileOutputStream;

public class JaxWsProviderWrapper extends Provider {
    private static final Logger logger = Logger.getLogger("OpenEJB.client");

    //
    // Magic to get our proider wrapper installed with the PortRefData
    //

    private static ThreadLocal<ProviderWrapperData> threadPortRefs = new ThreadLocal<ProviderWrapperData>();

    public static void beforeCreate(List<PortRefMetaData> portRefMetaDatas) {
        // Axis JAXWS api is non compliant and checks system property before classloader
        // so we replace system property so this wrapper is selected.  The original value
        // is saved into an openejb property so we can load the class in the find method
        String oldProperty = System.getProperty(JAXWSPROVIDER_PROPERTY);
        if (oldProperty != null && !oldProperty.equals(JaxWsProviderWrapper.class.getName())) {
            System.setProperty("openejb." + JAXWSPROVIDER_PROPERTY, oldProperty);
            System.setProperty(JAXWSPROVIDER_PROPERTY, JaxWsProviderWrapper.class.getName());
        }

        ClassLoader oldClassLoader = Thread.currentThread().getContextClassLoader();
        if (oldClassLoader != null) {
            Thread.currentThread().setContextClassLoader(new ProviderClassLoader(oldClassLoader));
        } else {
            Thread.currentThread().setContextClassLoader(new ProviderClassLoader());
        }
        threadPortRefs.set(new ProviderWrapperData(portRefMetaDatas, oldClassLoader));
    }

    public static void afterCreate() {
        Thread.currentThread().setContextClassLoader(threadPortRefs.get().callerClassLoader);
        threadPortRefs.set(null);
    }

    private static class ProviderWrapperData {
        private final List<PortRefMetaData> portRefMetaData;
        private final ClassLoader callerClassLoader;

        public ProviderWrapperData(List<PortRefMetaData> portRefMetaDatas, ClassLoader callerClassLoader) {
            this.portRefMetaData = portRefMetaDatas;
            this.callerClassLoader = callerClassLoader;
        }
    }


    //
    // Provider wappre implementation
    //

    private final Provider delegate;
    private final List<PortRefMetaData> portRefs;

    public JaxWsProviderWrapper() {
        delegate = findProvider();
        portRefs = threadPortRefs.get().portRefMetaData;
    }

    public Provider getDelegate() {
        return delegate;
    }

    public ServiceDelegate createServiceDelegate(URL wsdlDocumentLocation, QName serviceName, Class serviceClass) {
        ServiceDelegate serviceDelegate = delegate.createServiceDelegate(wsdlDocumentLocation, serviceName, serviceClass);
        serviceDelegate = new ServiceDelegateWrapper(serviceDelegate);
        return serviceDelegate;
    }

    public Endpoint createEndpoint(String bindingId, Object implementor) {
        return delegate.createEndpoint(bindingId, implementor);
    }

    public Endpoint createAndPublishEndpoint(String address, Object implementor) {
        return delegate.createAndPublishEndpoint(address, implementor);
    }

    private class ServiceDelegateWrapper extends ServiceDelegate {
        private final ServiceDelegate serviceDelegate;

        public ServiceDelegateWrapper(ServiceDelegate serviceDelegate) {
            this.serviceDelegate = serviceDelegate;
        }

        public <T> T getPort(QName portName, Class<T> serviceEndpointInterface) {
            T t = serviceDelegate.getPort(portName, serviceEndpointInterface);
            setProperties((BindingProvider) t);
            return t;
        }

        public <T> T getPort(Class<T> serviceEndpointInterface) {
            T t = serviceDelegate.getPort(serviceEndpointInterface);
            setProperties((BindingProvider) t);
            return t;
        }

        public void addPort(QName portName, String bindingId, String endpointAddress) {
            serviceDelegate.addPort(portName, bindingId, endpointAddress);
        }

        public <T> Dispatch<T> createDispatch(QName portName, Class<T> type, Service.Mode mode) {
            Dispatch<T> dispatch = serviceDelegate.createDispatch(portName, type, mode);
            setProperties(dispatch);
            return dispatch;
        }

        public Dispatch<Object> createDispatch(QName portName, JAXBContext context, Service.Mode mode) {
            Dispatch<Object> dispatch = serviceDelegate.createDispatch(portName, context, mode);
            setProperties(dispatch);
            return dispatch;
        }

        public QName getServiceName() {
            QName qName = serviceDelegate.getServiceName();
            return qName;
        }

        public Iterator<QName> getPorts() {
            Iterator<QName> ports = serviceDelegate.getPorts();
            return ports;
        }

        public URL getWSDLDocumentLocation() {
            URL documentLocation = serviceDelegate.getWSDLDocumentLocation();
            return documentLocation;
        }

        public HandlerResolver getHandlerResolver() {
            HandlerResolver handlerResolver = serviceDelegate.getHandlerResolver();
            return handlerResolver;
        }

        public void setHandlerResolver(HandlerResolver handlerResolver) {
            serviceDelegate.setHandlerResolver(handlerResolver);
        }

        public Executor getExecutor() {
            Executor executor = serviceDelegate.getExecutor();
            return executor;
        }

        public void setExecutor(Executor executor) {
            serviceDelegate.setExecutor(executor);
        }

        private void setProperties(BindingProvider proxy) {
            for (PortRefMetaData portRef : portRefs) {
                Class<?> intf = null;
                try {
                    intf = proxy.getClass().getClassLoader().loadClass(portRef.getServiceEndpointInterface());
                } catch (ClassNotFoundException e) {
                }
                if (intf != null && intf.isInstance(proxy)) {
                    // set mtom
                    boolean enableMTOM = portRef.isEnableMtom();
                    if (enableMTOM && proxy.getBinding() instanceof SOAPBinding) {
                        ((SOAPBinding)proxy.getBinding()).setMTOMEnabled(enableMTOM);
                    }

                    // set properties
                    for (Map.Entry<Object, Object> entry : portRef.getProperties().entrySet()) {
                        String name = (String) entry.getKey();
                        String value = (String) entry.getValue();
                        proxy.getRequestContext().put(name, value);
                    }

                    return;
                }
            }
        }
    }

    private static Provider findProvider() {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader == null) classLoader = ClassLoader.getSystemClassLoader();

        // 0. System.getProperty("openejb.javax.xml.ws.spi.Provider")
        // This is so those using old axis rules still work as expected
        String providerClass = System.getProperty("openejb." + JAXWSPROVIDER_PROPERTY);
        Provider provider = createProviderInstance(providerClass, classLoader);
        if (provider != null) {
            return provider;
        }

        // 1. META-INF/services/javax.xml.ws.spi.Provider
        try {
            for (URL url : Collections.list(classLoader.getResources("META-INF/services/" + JAXWSPROVIDER_PROPERTY))) {
                BufferedReader in = null;
                try {
                    in = new BufferedReader(new InputStreamReader(url.openStream()));

                    providerClass = in.readLine();
                    provider = createProviderInstance(providerClass, classLoader);
                    if (provider != null) {
                        return provider;
                    }
                } catch (Exception ignored) {
                } finally {
                    if (in != null) {
                        try {
                            in.close();
                        } catch (IOException e) {
                        }
                    }
                }
            }
        } catch (Exception ingored) {
        }

        // 2. $java.home/lib/jaxws.properties
        String javaHome = System.getProperty("java.home");
        File jaxrpcPropertiesFile = new File(new File(javaHome, "lib"), "jaxrpc.properties");
        if (jaxrpcPropertiesFile.exists()) {
            InputStream in = null;
            try {
                in = new FileInputStream(jaxrpcPropertiesFile);
                Properties properties = new Properties();
                properties.load(in);

                providerClass = properties.getProperty(JAXWSPROVIDER_PROPERTY);
                provider = createProviderInstance(providerClass, classLoader);
                if (provider != null) {
                    return provider;
                }
            } catch(Exception ignored) {
            } finally {
                if (in != null) {
                    try {
                        in.close();
                    } catch (IOException e) {
                    }
                }
            }
        }

        // 3. System.getProperty("javax.xml.ws.spi.Provider")
        providerClass = System.getProperty(JAXWSPROVIDER_PROPERTY);
        provider = createProviderInstance(providerClass, classLoader);
        if (provider != null) {
            return provider;
        }

        throw new WebServiceException("No " + JAXWSPROVIDER_PROPERTY + " implementation found");
    }

    private static Provider createProviderInstance(String providerClass, ClassLoader classLoader) {
        if (providerClass != null && providerClass.length() > 0 && !providerClass.equals(JaxWsProviderWrapper.class.getName())) {
            try {
                Class<? extends Provider> clazz = classLoader.loadClass(providerClass).asSubclass(Provider.class);
                return clazz.newInstance();
            } catch (Throwable e) {
                logger.log(Level.WARNING, "Unable to construct provider implementation " + providerClass, e);
            }
        }
        return null;
    }

    private static class ProviderClassLoader extends ClassLoader {
        private static final String PROVIDER_RESOURCE = "META-INF/services/" + JAXWSPROVIDER_PROPERTY;
        private static final URL PROVIDER_URL;
        static {
            try {
                File tempFile = File.createTempFile("openejb-jaxws-provider", "tmp");
                OutputStream out = new FileOutputStream(tempFile);
                out.write(JaxWsProviderWrapper.class.getName().getBytes());
                out.close();
                PROVIDER_URL = tempFile.toURL();
            } catch (IOException e) {
                throw new RuntimeException("Cound not create openejb-jaxws-provider file");
            }
        }

        public ProviderClassLoader() {
        }

        public ProviderClassLoader(ClassLoader parent) {
            super(parent);
        }

        public Enumeration<URL> getResources(String name) throws IOException {
            Enumeration<URL> resources = super.getResources(name);
            if (PROVIDER_RESOURCE.equals(name)) {
                ArrayList<URL> list = new ArrayList<URL>();
                list.add(PROVIDER_URL);
                list.addAll(Collections.list(resources));
                resources = Collections.enumeration(list);
            }
            return resources;
        }


        public URL getResource(String name) {
            if (PROVIDER_RESOURCE.equals(name)) {
                return PROVIDER_URL;
            }
            return super.getResource(name);
        }
    }
}
