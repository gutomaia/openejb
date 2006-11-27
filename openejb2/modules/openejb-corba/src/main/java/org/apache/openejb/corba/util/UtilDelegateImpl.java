/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.openejb.corba.util;

import java.rmi.AccessException;
import java.rmi.MarshalException;
import java.rmi.NoSuchObjectException;
import java.rmi.Remote;
import java.rmi.RemoteException;
import javax.rmi.CORBA.Stub;
import javax.rmi.CORBA.Tie;
import javax.rmi.CORBA.UtilDelegate;
import javax.rmi.CORBA.ValueHandler;
import javax.transaction.InvalidTransactionException;
import javax.transaction.TransactionRequiredException;
import javax.transaction.TransactionRolledbackException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.omg.CORBA.CompletionStatus;
import org.omg.CORBA.INVALID_TRANSACTION;
import org.omg.CORBA.MARSHAL;
import org.omg.CORBA.NO_PERMISSION;
import org.omg.CORBA.OBJECT_NOT_EXIST;
import org.omg.CORBA.ORB;
import org.omg.CORBA.SystemException;
import org.omg.CORBA.TRANSACTION_REQUIRED;
import org.omg.CORBA.TRANSACTION_ROLLEDBACK;
import org.omg.CORBA.UNKNOWN;
import org.omg.CORBA.portable.InputStream;
import org.omg.CORBA.portable.OutputStream;

import org.apache.openejb.corba.AdapterWrapper;
import org.apache.openejb.corba.CORBAException;
import org.apache.openejb.corba.RefGenerator;
import org.apache.openejb.corba.StandardServant;
import org.apache.openejb.proxy.BaseEJB;
import org.apache.openejb.proxy.EJBHomeImpl;
import org.apache.openejb.proxy.EJBObjectImpl;
import org.apache.openejb.proxy.ProxyInfo;
import org.apache.openejb.EJBInterfaceType;


/**
 * @version $Revision$ $Date$
 */
public final class UtilDelegateImpl implements UtilDelegate {

    private final Log log = LogFactory.getLog(UtilDelegateImpl.class);
    private final UtilDelegate delegate;
    private static ClassLoader classLoader;

    private final static String DELEGATE_NAME = "org.apache.openejb.corba.UtilDelegateClass";

    public UtilDelegateImpl() throws ClassNotFoundException, IllegalAccessException, InstantiationException {
        String value = System.getProperty(DELEGATE_NAME);
        if (value == null) {
            log.error("No delegate specfied via " + DELEGATE_NAME);
            throw new IllegalStateException("The property " + DELEGATE_NAME + " must be defined!");
        }

        if (log.isDebugEnabled()) log.debug("Set delegate " + value);
        delegate = (UtilDelegate) Class.forName(value).newInstance();
    }

    static void setClassLoader(ClassLoader classLoader) {
        UtilDelegateImpl.classLoader = classLoader;
    }

    public void unexportObject(Remote target) throws NoSuchObjectException {
        delegate.unexportObject(target);
    }

    public boolean isLocal(Stub stub) throws RemoteException {
        return delegate.isLocal(stub);
    }

    public ValueHandler createValueHandler() {
        return delegate.createValueHandler();
    }

    public Object readAny(InputStream in) {
        return delegate.readAny(in);
    }

    public void writeAbstractObject(OutputStream out, Object obj) {
        delegate.writeAbstractObject(out, obj);
    }

    public void writeAny(OutputStream out, Object obj) {
        delegate.writeAny(out, obj);
    }

    public void writeRemoteObject(OutputStream out, Object obj) {
        try {
            if (obj instanceof Tie && ((Tie) obj).getTarget() instanceof BaseEJB) {
                obj = ((Tie) obj).getTarget();
            }
            if (obj instanceof BaseEJB) {
                obj = convertEJBToCORBAObject((BaseEJB) obj);
            }
            if (obj instanceof StandardServant) {
                StandardServant servant = (StandardServant) obj;
                EJBInterfaceType servantType = servant.getEjbInterfaceType();
                ProxyInfo proxyInfo = servant.getEjbContainer().getProxyInfo();
                try {
                    RefGenerator refGenerator = AdapterWrapper.getRefGenerator(proxyInfo.getContainerID());
                    if (refGenerator == null) {
                        throw new MARSHAL("Could not find RefGenerator for container ID: " + proxyInfo.getContainerID());
                    }
                    if (EJBInterfaceType.HOME == servantType) {
                        obj = refGenerator.genHomeReference(proxyInfo);
                    } else if (EJBInterfaceType.REMOTE == servantType) {
                        obj = refGenerator.genObjectReference(proxyInfo);
                    } else {
                        log.error("Encountered unknown local invocation handler of type " + servantType + ":" + proxyInfo);
                        throw new MARSHAL("Internal server error while marshaling the reply", 0, CompletionStatus.COMPLETED_YES);
                    }
                } catch (CORBAException e) {
                    log.error("Encountered unknown local invocation handler of type " + servantType + ":" + proxyInfo);
                    throw new MARSHAL("Internal server error while marshaling the reply", 0, CompletionStatus.COMPLETED_YES);
                }
            }
            delegate.writeRemoteObject(out, obj);
        } catch (Throwable e) {
            log.error("Received unexpected exception while marshaling an object reference:", e);
            throw new MARSHAL("Internal server error while marshaling the reply", 0, CompletionStatus.COMPLETED_YES);
        }
    }

    public String getCodebase(Class clz) {
        return delegate.getCodebase(clz);
    }

    public void registerTarget(Tie tie, Remote target) {
        delegate.registerTarget(tie, target);
    }

    public RemoteException wrapException(Throwable obj) {
        return delegate.wrapException(obj);
    }

    public RemoteException mapSystemException(SystemException ex) {
        if (ex instanceof TRANSACTION_ROLLEDBACK) {
            TransactionRolledbackException transactionRolledbackException = new TransactionRolledbackException(ex.getMessage());
            transactionRolledbackException.detail = ex;
            return transactionRolledbackException;
        }
        if (ex instanceof TRANSACTION_REQUIRED) {
            TransactionRequiredException transactionRequiredException = new TransactionRequiredException(ex.getMessage());
            transactionRequiredException.detail = ex;
            return transactionRequiredException;
        }
        if (ex instanceof INVALID_TRANSACTION) {
            InvalidTransactionException invalidTransactionException = new InvalidTransactionException(ex.getMessage());
            invalidTransactionException.detail = ex;
            return invalidTransactionException;
        }
        if (ex instanceof OBJECT_NOT_EXIST) {
            NoSuchObjectException noSuchObjectException = new NoSuchObjectException(ex.getMessage());
            noSuchObjectException.detail = ex;
            return noSuchObjectException;
        }
        if (ex instanceof NO_PERMISSION) {
            return new AccessException(ex.getMessage(), ex);
        }
        if (ex instanceof MARSHAL) {
            return new MarshalException(ex.getMessage(), ex);
        }
        if (ex instanceof UNKNOWN) {
            return new RemoteException(ex.getMessage(), ex);
        }
        return delegate.mapSystemException(ex);
    }

    public Tie getTie(Remote target) {
        return delegate.getTie(target);
    }

    public Object copyObject(Object obj, ORB orb) throws RemoteException {
        return delegate.copyObject(obj, orb);
    }

    public Object[] copyObjects(Object[] obj, ORB orb) throws RemoteException {
        return delegate.copyObjects(obj, orb);
    }

    public Class loadClass(String className, String remoteCodebase, ClassLoader loader) throws ClassNotFoundException {
        if (log.isDebugEnabled()) log.debug("Load class: " + className + ", " + remoteCodebase + ", " + loader);

        Class result = null;
        try {
            result = delegate.loadClass(className, remoteCodebase, loader);
        } catch (ClassNotFoundException e) {
            if (log.isDebugEnabled()) log.debug("Unable to load class from delegate");
        }
        if (result == null && classLoader != null) {
            if (log.isDebugEnabled()) log.debug("Attempting to load " + className + " from the static class loader");

            try {
                result = classLoader.loadClass(className);
            } catch (ClassNotFoundException e) {
                if (log.isDebugEnabled()) log.debug("Unable to load " + className + " from the static class loader");
                throw e;
            }

            if (log.isDebugEnabled()) log.debug("result: " + (result == null ? "NULL" : result.getName()));
        }

        return result;
    }

    /**
     * handle activation
     */
    private org.omg.CORBA.Object convertEJBToCORBAObject(BaseEJB proxy) {
        ProxyInfo pi = proxy.getProxyInfo();
        try {
            RefGenerator refGenerator = AdapterWrapper.getRefGenerator(pi.getContainerID());
            if (refGenerator == null) {
                throw new MARSHAL("Could not find RefGenerator for container ID: " + pi.getContainerID());
            }
            if (proxy instanceof EJBHomeImpl) {
                return refGenerator.genHomeReference(pi);
            } else if (proxy instanceof EJBObjectImpl) {
                return refGenerator.genObjectReference(pi);
            } else {
                log.error("Encountered unknown local invocation handler of type " + proxy.getClass().getSuperclass() + ":" + pi);
                throw new MARSHAL("Internal server error while marshaling the reply", 0, CompletionStatus.COMPLETED_YES);
            }
        } catch (CORBAException e) {
            log.error("Encountered unknown local invocation handler of type " + proxy.getClass().getSuperclass() + ":" + pi);
            throw new MARSHAL("Internal server error while marshaling the reply", 0, CompletionStatus.COMPLETED_YES);
        }
    }
}
