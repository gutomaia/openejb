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
package org.apache.openejb;

/**
 *
 *
 *
 * @version $Revision$ $Date$
 */
public final class TransactionDemarcation {
    public static final TransactionDemarcation CONTAINER = new TransactionDemarcation("Container");
    public static final TransactionDemarcation BEAN = new TransactionDemarcation("Bean");

    private final String name;

    private TransactionDemarcation(String name) {
        this.name = name;
    }

    public boolean isContainer() {
        return this == CONTAINER;
    }

    public boolean isBean() {
        return this == BEAN;
    }

    public String toString() {
        return name;
    }

    public static TransactionDemarcation valueOf(String demarcation) {
        if (CONTAINER.name.equals(demarcation)) {
            return CONTAINER;
        } else if (BEAN.name.equals(demarcation)) {
            return BEAN;
        } else {
            throw new IllegalArgumentException("Invalid demarcation type: "+demarcation);
        }
    }


}
