// Copyright 2020-2023 The NATS Authors
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

package io.nats.client.impl;

import io.nats.client.ConsumeOptions;
import io.nats.client.JetStreamApiException;
import io.nats.client.MessageHandler;
import io.nats.client.PullRequestOptions;

import java.io.IOException;

class NatsSimpleConsumer extends NatsSimpleConsumerBase {
    protected final PullRequestOptions repullPro;
    protected final int thresholdMessages;
    protected final int thresholdBytes;
    protected final NatsConsumerContext.Mediator mediator;

    NatsSimpleConsumer(NatsConsumerContext.Mediator mediator, final MessageHandler messageHandler, ConsumeOptions opts) throws IOException, JetStreamApiException {
        this.mediator = mediator;
        if (messageHandler == null) {
            initSub(mediator.makeSubscription(null));
        }
        else {
            initSub(mediator.makeSubscription(msg -> {
                checkForRepull();
                messageHandler.onMessage(msg);
            }));
        }

        int bm = opts.getBatchSize();
        int bb = opts.getBatchBytes();

        PullRequestOptions firstPro = PullRequestOptions.builder(bm)
            .maxBytes(bb)
            .expiresIn(opts.getExpires())
            .idleHeartbeat(opts.getIdleHeartbeat())
            .build();

        int repullMessages = Math.max(1, bm * opts.getThresholdPercent() / 100);
        int repullBytes = bb == 0 ? 0 : Math.max(1, bb * opts.getThresholdPercent() / 100);
        repullPro = PullRequestOptions.builder(repullMessages)
            .maxBytes(repullBytes)
            .expiresIn(opts.getExpires())
            .idleHeartbeat(opts.getIdleHeartbeat())
            .build();

        thresholdMessages = bm - repullMessages;
        thresholdBytes = bb == 0 ? Integer.MIN_VALUE : bb - repullBytes;
        sub.pull(firstPro);
    }

    protected void checkForRepull() {
        if (active &&
            (pmm.pendingMessages <= thresholdMessages || pmm.pendingBytes <= thresholdBytes))
        {
            sub.pull(repullPro);
        }
    }
}
