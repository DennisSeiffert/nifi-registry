/*
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
package org.apache.nifi.registry.model.authorization;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;

@ApiModel("accessStatus")
public class AccessStatus {

    public static enum Status {
        UNKNOWN,
        ACTIVE
    }

    private String identity;
    private Status status;
    private String message;

    @ApiModelProperty(
            value = "The user identity.",
            readOnly = true
    )
    public String getIdentity() {
        return identity;
    }

    public void setIdentity(String identity) {
        this.identity = identity;
    }

    @ApiModelProperty(
            value = "The user access status.",
            readOnly = true
    )
    public String getStatus() {
        return status.toString();
    }

    public void setStatus(String status) {
        this.status = Status.valueOf(status);
    }

    @ApiModelProperty(
            value = "Additional details about the user access status.",
            readOnly = true
    )
    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}