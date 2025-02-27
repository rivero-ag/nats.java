// Copyright 2020 The NATS Authors
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at:
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package io.nats.client.api;

import io.nats.client.support.JsonValue;

/**
 * Information about an upstream stream source in a mirror
 */
public class MirrorInfo extends SourceInfoBase {

    static MirrorInfo optionalInstance(JsonValue vMirror) {
        return vMirror == null ? null : new MirrorInfo(vMirror);
    }

    MirrorInfo(JsonValue vMirror) {
        super(vMirror);
    }

    @Override
    public String toString() {
        return "Mirror" + super.toString();
    }
}
