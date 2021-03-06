/*
 * Copyright 2011-2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.lettuce.core.commands.reactive;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import reactor.test.StepVerifier;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.KeyValue;
import io.lettuce.core.RedisException;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.reactive.RedisReactiveCommands;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.commands.TransactionCommandTest;
import io.lettuce.util.ReactiveSyncInvocationHandler;

/**
 * @author Mark Paluch
 */
public class TransactionReactiveCommandTest extends TransactionCommandTest {

    private RedisReactiveCommands<String, String> commands;

    @Override
    protected RedisCommands<String, String> connect() {
        return ReactiveSyncInvocationHandler.sync(client.connect());
    }

    @Before
    public void openConnection() {
        client.setOptions(ClientOptions.builder().build());
        redis = connect();
        redis.flushall();
        redis.flushdb();

        commands = redis.getStatefulConnection().reactive();
    }

    @After
    public void closeConnection() {
        redis.getStatefulConnection().close();
    }

    @Test
    public void discard() {

        StepVerifier.create(commands.multi()).expectNext("OK").verifyComplete();

        commands.set(key, value).toProcessor();

        StepVerifier.create(commands.discard()).expectNext("OK").verifyComplete();
        StepVerifier.create(commands.get(key)).verifyComplete();
    }

    @Test
    public void watchRollback() {

        StatefulRedisConnection<String, String> otherConnection = client.connect();

        otherConnection.sync().set(key, value);

        StepVerifier.create(commands.watch(key)).expectNext("OK").verifyComplete();
        StepVerifier.create(commands.multi()).expectNext("OK").verifyComplete();

        commands.set(key, value).toProcessor();

        otherConnection.sync().del(key);

        StepVerifier.create(commands.exec()).consumeNextWith(actual -> {
            assertThat(actual).isNotNull();
            assertThat(actual.wasDiscarded()).isTrue();
        });
    }

    @Test
    public void execSingular() {

        StepVerifier.create(commands.multi()).expectNext("OK").verifyComplete();

        redis.set(key, value);

        StepVerifier.create(commands.exec()).consumeNextWith(actual -> assertThat(actual).contains("OK")).verifyComplete();
        StepVerifier.create(commands.get(key)).expectNext(value).verifyComplete();
    }

    @Test
    public void errorInMulti() {

        commands.multi().toProcessor();
        commands.set(key, value).toProcessor();
        commands.lpop(key).toProcessor();
        commands.get(key).toProcessor();

        StepVerifier.create(commands.exec()).consumeNextWith(actual -> {

            assertThat((String) actual.get(0)).isEqualTo("OK");
            assertThat(actual.get(1) instanceof RedisException).isTrue();
            assertThat((String) actual.get(2)).isEqualTo(value);
        }).verifyComplete();
    }

    @Test
    public void resultOfMultiIsContainedInCommandFlux() {

        commands.multi().toProcessor();

        StepVerifier.Step<String> set1 = StepVerifier.create(commands.set("key1", "value1")).expectNext("OK").thenAwait();
        StepVerifier.Step<String> set2 = StepVerifier.create(commands.set("key2", "value2")).expectNext("OK").thenAwait();
        StepVerifier.Step<KeyValue<String, String>> mget = StepVerifier.create(commands.mget("key1", "key2"))
                .expectNext(KeyValue.just("key1", "value1"), KeyValue.just("key2", "value2")).thenAwait();
        StepVerifier.Step<Long> llen = StepVerifier.create(commands.llen("something")).expectNext(0L).thenAwait();

        StepVerifier.create(commands.exec()).then(() -> {

            set1.verifyComplete();
            set2.verifyComplete();
            mget.verifyComplete();
            llen.verifyComplete();

        }).expectNextCount(1).verifyComplete();
    }

    @Test
    public void resultOfMultiIsContainedInExecObservable() {

        commands.multi().toProcessor();
        commands.set("key1", "value1").toProcessor();
        commands.set("key2", "value2").toProcessor();
        commands.mget("key1", "key2").collectList().toProcessor();
        commands.llen("something").toProcessor();

        StepVerifier.create(commands.exec()).consumeNextWith(actual -> {

            assertThat(actual).contains("OK", "OK", list(kv("key1", "value1"), kv("key2", "value2")), 0L);

        }).verifyComplete();
    }
}
