/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 * 
 * Copyright 1997-2007 Sun Microsystems, Inc. All rights reserved.
 * 
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License. You can obtain
 * a copy of the License at https://glassfish.dev.java.net/public/CDDL+GPL.html
 * or glassfish/bootstrap/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 * 
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/bootstrap/legal/LICENSE.txt.
 * Sun designates this particular file as subject to the "Classpath" exception
 * as provided by Sun in the GPL Version 2 section of the License file that
 * accompanied this code.  If applicable, add the following below the License
 * Header, with the fields enclosed by brackets [] replaced by your own
 * identifying information: "Portions Copyrighted [year]
 * [name of copyright owner]"
 * 
 * Contributor(s):
 * 
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
package org.glassfish.ejb.deployment.annotation.handlers;

import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.lang.reflect.ParameterizedType;

import java.util.Set;
import java.util.concurrent.Future;
import java.util.logging.Level;

import javax.ejb.*;

import com.sun.enterprise.deployment.util.TypeUtil;
import com.sun.enterprise.deployment.EjbDescriptor;
import com.sun.enterprise.deployment.MethodDescriptor;

import org.glassfish.apf.AnnotationInfo;
import org.glassfish.apf.AnnotatedElementHandler;
import org.glassfish.apf.AnnotationProcessorException;
import org.glassfish.apf.HandlerProcessingResult;
import com.sun.enterprise.deployment.annotation.context.EjbContext;
import com.sun.enterprise.deployment.annotation.handlers.AbstractAttributeHandler;
import com.sun.enterprise.deployment.annotation.handlers.PostProcessor;
import org.jvnet.hk2.annotations.Service;

/**
 * This handler is responsible for handling the javax.ejb.Asynchronous
 * annotation on the Bean class. Annotation on the interfaces or specific 
 * methods on the interfaces are processed by the AbstractEjbHandler (with
 * the calls to the same #setAsynchronous() method call here.
 *
 * @author Marina Vatkina
 */
@Service
public class AsynchronousHandler extends AbstractAttributeHandler
        implements PostProcessor {

    public AsynchronousHandler() {
    }
    
    /**
     * @return the annoation type this annotation handler is handling
     */
    public Class<? extends Annotation> getAnnotationType() {
        return Asynchronous.class;
    }    
        
    protected HandlerProcessingResult processAnnotation(AnnotationInfo ainfo,
            EjbContext[] ejbContexts) throws AnnotationProcessorException {
        
        for (EjbContext ejbContext : ejbContexts) {
            EjbDescriptor ejbDesc = ejbContext.getDescriptor();

            if (ElementType.TYPE.equals(ainfo.getElementType())) {
                ejbContext.addPostProcessInfo(ainfo, this);
            } else {
                Method annMethod = (Method) ainfo.getAnnotatedElement();
                setAsynchronous(annMethod, ejbDesc);
            }
        }

        return getDefaultProcessedResult();
    }

    /**
     * @return an array of annotation types this annotation handler would 
     * require to be processed (if present) before it processes it's own 
     * annotation type.
     */
    public Class<? extends Annotation>[] getTypeDependencies() {
        
        return new Class[] {
            Local.class, Remote.class, Stateful.class, Stateless.class, Singleton.class};
                
    }

    protected boolean supportTypeInheritance() {
        return true;
    }

    /**
     * Set the default value (from class type annotation) on all
     * methods that don't have a value.
     * Class type annotation applies to all EJB 3.x Local/Remote/no-interface 
     * views in which  that  business method is exposed for that bean. 
     */
    public void postProcessAnnotation(AnnotationInfo ainfo,
            AnnotatedElementHandler aeHandler)
            throws AnnotationProcessorException {
        EjbContext ejbContext = (EjbContext)aeHandler;
        EjbDescriptor ejbDesc = ejbContext.getDescriptor();
        Class classAn = (Class)ainfo.getAnnotatedElement();

        Method[] methods = classAn.getDeclaredMethods();
        for (Method m0 : methods) {
            setAsynchronous(m0, ejbDesc);
        }
    }

    protected void setAsynchronous(Method m0, EjbDescriptor ejbDesc) 
            throws AnnotationProcessorException {
        Set mds = ejbDesc.getClientBusinessMethodDescriptors();
        for (Object next : mds) {
            MethodDescriptor nextDesc = (MethodDescriptor) next;
            Method m = nextDesc.getMethod(ejbDesc);
            if(sameAsynchronousMethodSignature(m, m0)) {
                // override by xml

                /** XXX TODO: Compare that the Future type matches return type
                 when @Asynchronous is on the interface as we don't look
                 at bean methods in that case. **/

                // Check return type on the business method
                checkValidReturnType(m);

                if (logger.isLoggable(Level.FINE)) {
                    logger.fine("Setting asynchronous flag on " + nextDesc);
                }
                nextDesc.setAsynchronous(true);
                break;
            }
        }
    }

    /**
     * Verify that the return type is void or Future<V>
     */
    private void checkValidReturnType(Method m) throws AnnotationProcessorException {
        if ( !(m.getReturnType().equals(Void.TYPE) ||
                m.getReturnType().equals(Future.class)) ) {
            throw new AnnotationProcessorException("Return type of a method " + m +
                    "annotated as @Asynchronous is not void or Future<V>");
        }
    }

    /**
     * Returns true if two methods have the same signatures 
     * or if one returns a Future of the return type of the other
     */
    private boolean sameAsynchronousMethodSignature(Method asyncm, Method otherm) {
        if(TypeUtil.sameMethodSignature(asyncm, otherm)) {
            return true;
        } else if ((asyncm.getName().equals(otherm.getName())) &&
                TypeUtil.sameParamTypes(asyncm, otherm)  && 
                asyncm.getReturnType().equals(Future.class) ) {

            // If it's not the same return type, it should be a Future<V> vs. <V>
            Type asyncmt = asyncm.getGenericReturnType();
            Type othermt = otherm.getGenericReturnType();

            return othermt.equals(
                    ((ParameterizedType)asyncmt).getActualTypeArguments()[0]); 
        }
        return false;
    }
}
