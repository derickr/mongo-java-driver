/*
 * Copyright (c) 2008-2014 MongoDB, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.mongodb.operation

import com.mongodb.MongoCommandException
import com.mongodb.MongoNamespace
import com.mongodb.MongoWriteConcernException
import com.mongodb.OperationFunctionalSpecification
import com.mongodb.WriteConcern
import com.mongodb.client.model.CreateCollectionOptions
import com.mongodb.client.model.ValidationOptions
import com.mongodb.client.test.CollectionHelper
import com.mongodb.client.test.Worker
import com.mongodb.client.test.WorkerCodec
import org.bson.BsonBoolean
import org.bson.BsonDocument
import org.bson.BsonDocumentWrapper
import org.bson.BsonInt32
import org.bson.BsonInt64
import org.bson.BsonObjectId
import org.bson.BsonString
import org.bson.Document
import org.bson.codecs.BsonDocumentCodec
import org.bson.codecs.DocumentCodec
import spock.lang.IgnoreIf

import java.util.concurrent.TimeUnit

import static com.mongodb.ClusterFixture.isDiscoverableReplicaSet
import static com.mongodb.ClusterFixture.serverVersionAtLeast
import static com.mongodb.client.model.Filters.gte

class FindAndUpdateOperationSpecification extends OperationFunctionalSpecification {
    private final DocumentCodec documentCodec = new DocumentCodec()
    private final WorkerCodec workerCodec = new WorkerCodec()
    def writeConcern = WriteConcern.ACKNOWLEDGED

    def 'should have the correct defaults and passed values'() {
        when:
        def update = new BsonDocument('update', new BsonInt32(1))
        def operation = new FindAndUpdateOperation<Document>(getNamespace(), writeConcern, documentCodec, update)

        then:
        operation.getNamespace() == getNamespace()
        operation.getWriteConcern() == writeConcern
        operation.getDecoder() == documentCodec
        operation.getUpdate() == update
        operation.getFilter() == null
        operation.getSort() == null
        operation.getProjection() == null
        operation.getMaxTime(TimeUnit.SECONDS) == 0
        operation.getBypassDocumentValidation() == null
        operation.getCollation() == null
    }

    def 'should set optional values correctly'(){
        given:
        def filter = new BsonDocument('filter', new BsonInt32(1))
        def sort = new BsonDocument('sort', new BsonInt32(1))
        def projection = new BsonDocument('projection', new BsonInt32(1))

        when:
        def operation = new FindAndUpdateOperation<Document>(getNamespace(), writeConcern, documentCodec,
                new BsonDocument('update', new BsonInt32(1))).filter(filter).sort(sort).projection(projection)
                .bypassDocumentValidation(true).maxTime(1, TimeUnit.SECONDS).upsert(true)
                .returnOriginal(false)
                .collation(defaultCollation)

        then:
        operation.getFilter() == filter
        operation.getSort() == sort
        operation.getProjection() == projection
        operation.upsert == true
        operation.getMaxTime(TimeUnit.SECONDS) == 1
        operation.getBypassDocumentValidation()
        !operation.isReturnOriginal()
        operation.getCollation() == defaultCollation
    }

    def 'should update single document'() {

        given:
        CollectionHelper<Document> helper = new CollectionHelper<Document>(documentCodec, getNamespace())
        Document pete = new Document('name', 'Pete').append('numberOfJobs', 3)
        Document sam = new Document('name', 'Sam').append('numberOfJobs', 5)

        helper.insertDocuments(new DocumentCodec(), pete, sam)

        when:
        def update = new BsonDocument('$inc', new BsonDocument('numberOfJobs', new BsonInt32(1)))
        def operation = new FindAndUpdateOperation<Document>(getNamespace(), writeConcern, documentCodec, update)
                .filter(new BsonDocument('name', new BsonString('Pete')))
        Document returnedDocument = execute(operation, async)

        then:
        returnedDocument.getInteger('numberOfJobs') == 3
        helper.find().size() == 2;
        helper.find().get(0).getInteger('numberOfJobs') == 4

        when:
        update = new BsonDocument('$inc', new BsonDocument('numberOfJobs', new BsonInt32(1)))
        operation = new FindAndUpdateOperation<Document>(getNamespace(), writeConcern, documentCodec, update)
                .filter(new BsonDocument('name', new BsonString('Pete')))
                .returnOriginal(false)
        returnedDocument = execute(operation, async)

        then:
        returnedDocument.getInteger('numberOfJobs') == 5

        where:
        async << [true, false]
    }

    def 'should update single document when using custom codecs'() {
        given:
        CollectionHelper<Worker> helper = new CollectionHelper<Worker>(workerCodec, getNamespace())
        Worker pete = new Worker('Pete', 'handyman', new Date(), 3)
        Worker sam = new Worker('Sam', 'plumber', new Date(), 5)

        helper.insertDocuments(new WorkerCodec(), pete, sam)

        when:
        def update = new BsonDocument('$inc', new BsonDocument('numberOfJobs', new BsonInt32(1)))
        def operation = new FindAndUpdateOperation<Worker>(getNamespace(), writeConcern, workerCodec, update)
                .filter(new BsonDocument('name', new BsonString('Pete')))
        Worker returnedDocument = execute(operation, async)

        then:
        returnedDocument.numberOfJobs == 3
        helper.find().size() == 2;
        helper.find().get(0).numberOfJobs == 4

        when:
        update = new BsonDocument('$inc', new BsonDocument('numberOfJobs', new BsonInt32(1)))
        operation = new FindAndUpdateOperation<Worker>(getNamespace(), writeConcern, workerCodec, update)
                .filter(new BsonDocument('name', new BsonString('Pete')))
                .returnOriginal(false)
        returnedDocument = execute(operation, async)

        then:
        returnedDocument.numberOfJobs == 5

        where:
        async << [true, false]
    }

    def 'should return null if query fails to match'() {
        when:
        def update = new BsonDocument('$inc', new BsonDocument('numberOfJobs', new BsonInt32(1)))
        def operation = new FindAndUpdateOperation<Document>(getNamespace(), writeConcern, documentCodec, update)
                .filter(new BsonDocument('name', new BsonString('Pete')))
        Document returnedDocument = execute(operation, async)

        then:
        returnedDocument == null

        where:
        async << [true, false]
    }

    def 'should throw an exception if update contains fields that are not update operators'() {
        given:
        def update = new BsonDocument('x', new BsonInt32(1))
        def operation = new FindAndUpdateOperation<Document>(getNamespace(), writeConcern, documentCodec, update)

        when:
        execute(operation, async)

        then:
        thrown(IllegalArgumentException)

        where:
        async << [true, false]
    }

    @IgnoreIf({ !serverVersionAtLeast(3, 2) })
    def 'should support bypassDocumentValidation'() {
        given:
        def namespace = new MongoNamespace(getDatabaseName(), 'collectionOut')
        def collectionHelper = getCollectionHelper(namespace)
        collectionHelper.create('collectionOut', new CreateCollectionOptions().validationOptions(
                new ValidationOptions().validator(gte('level', 10))))
        collectionHelper.insertDocuments(BsonDocument.parse('{ level: 10 }'))

        when:
        def update = new BsonDocument('$inc', new BsonDocument('level', new BsonInt32(-1)))
        def operation = new FindAndUpdateOperation<Document>(namespace, writeConcern, documentCodec, update)
        execute(operation, async)

        then:
        thrown(MongoCommandException)

        when:
        operation.bypassDocumentValidation(false)
        execute(operation, async)

        then:
        thrown(MongoCommandException)

        when:
        operation.bypassDocumentValidation(true).returnOriginal(false)
        Document returnedDocument = execute(operation, async)

        then:
        notThrown(MongoCommandException)
        returnedDocument.getInteger('level') == 9

        cleanup:
        collectionHelper?.drop()

        where:
        async << [true, false]
    }

    @IgnoreIf({ !serverVersionAtLeast(3, 2) || !isDiscoverableReplicaSet() })
    def 'should throw on write concern error'() {
        given:
        getCollectionHelper().insertDocuments(new DocumentCodec(), new Document('name', 'Pete'))
        def update = new BsonDocument('$inc', new BsonDocument('numberOfJobs', new BsonInt32(1)))

        when:
        def operation = new FindAndUpdateOperation<Document>(getNamespace(), new WriteConcern(5, 1), documentCodec, update)
                .filter(new BsonDocument('name', new BsonString('Pete')))
        execute(operation, async)

        then:
        def ex = thrown(MongoWriteConcernException)
        ex.writeConcernError.code == 100
        !ex.writeConcernError.message.isEmpty()
        ex.writeResult.count == 1
        ex.writeResult.updateOfExisting
        ex.writeResult.upsertedId == null

        when:
        operation = new FindAndUpdateOperation<Document>(getNamespace(), new WriteConcern(5, 1), documentCodec, update)
                .filter(new BsonDocument('name', new BsonString('Bob')))
                .upsert(true)
        execute(operation, async)

        then:
        ex = thrown(MongoWriteConcernException)
        ex.writeResult.count == 1
        !ex.writeResult.updateOfExisting
        ex.writeResult.upsertedId instanceof BsonObjectId

        where:
        async << [true, false]
    }

    def 'should create the expected command'() {
        when:
        def cannedResult = new BsonDocument('value', new BsonDocumentWrapper(BsonDocument.parse('{}'), new BsonDocumentCodec()))
        def update = BsonDocument.parse('{ update: 1}')
        def operation = new FindAndUpdateOperation<Document>(getNamespace(), writeConcern, documentCodec, update)
        def expectedCommand = new BsonDocument('findandmodify', new BsonString(getNamespace().getCollectionName()))
                .append('update', update)
        if (includeWriteConcern) {
            expectedCommand.put('writeConcern', writeConcern.asDocument())
        }

        then:
        testOperation(operation, serverVersion, expectedCommand, async, cannedResult)

        when:
        def filter = BsonDocument.parse('{ filter : 1}')
        def sort = BsonDocument.parse('{ sort : 1}')
        def projection = BsonDocument.parse('{ projection : 1}')

        operation.filter(filter)
                .sort(sort)
                .projection(projection)
                .bypassDocumentValidation(true)
                .maxTime(10, TimeUnit.MILLISECONDS)

        expectedCommand.append('query', filter)
                .append('sort', sort)
                .append('fields', projection)
                .append('maxTimeMS', new BsonInt64(10))

        if (includeCollation) {
            operation.collation(defaultCollation)
            expectedCommand.append('collation', defaultCollation.asDocument())
        }
        if (includeBypassValidation) {
            expectedCommand.append('bypassDocumentValidation', BsonBoolean.TRUE)
        }

        then:
        testOperation(operation, serverVersion, expectedCommand, async, cannedResult)

        where:
        serverVersion  | writeConcern                 | includeWriteConcern   | includeCollation | includeBypassValidation | async
        [3, 4, 0]      | WriteConcern.W1              | true                  | true             | true                    | true
        [3, 4, 0]      | WriteConcern.ACKNOWLEDGED    | false                 | true             | true                    | true
        [3, 4, 0]      | WriteConcern.UNACKNOWLEDGED  | false                 | true             | true                    | true
        [3, 4, 0]      | WriteConcern.W1              | true                  | true             | true                    | false
        [3, 4, 0]      | WriteConcern.ACKNOWLEDGED    | false                 | true             | true                    | false
        [3, 4, 0]      | WriteConcern.UNACKNOWLEDGED  | false                 | true             | true                    | false
        [3, 0, 0]      | WriteConcern.ACKNOWLEDGED    | false                 | false            | false                   | true
        [3, 0, 0]      | WriteConcern.UNACKNOWLEDGED  | false                 | false            | false                   | true
        [3, 0, 0]      | WriteConcern.W1              | false                 | false            | false                   | true
        [3, 0, 0]      | WriteConcern.ACKNOWLEDGED    | false                 | false            | false                   | false
        [3, 0, 0]      | WriteConcern.UNACKNOWLEDGED  | false                 | false            | false                   | false
        [3, 0, 0]      | WriteConcern.W1              | false                 | false            | false                   | false
    }

    def 'should throw an exception when passing an unsupported collation'() {
        given:
        def update = BsonDocument.parse('{ $set: {x: 1}}')
        def operation = new FindAndUpdateOperation<Document>(getNamespace(), writeConcern, documentCodec, update)
                .collation(defaultCollation)

        when:
        testOperationThrows(operation, [3, 2, 0], async)

        then:
        def exception = thrown(IllegalArgumentException)
        exception.getMessage().startsWith('Collation not supported by server version:')

        where:
        async << [false, false]
    }

    @IgnoreIf({ !serverVersionAtLeast(3, 4) })
    def 'should support collation'() {
        given:
        def document = Document.parse('{_id: 1, str: "foo"}')
        getCollectionHelper().insertDocuments(document)
        def update = BsonDocument.parse('{ $set: {str: "bar"}}')
        def operation = new FindAndUpdateOperation<Document>(getNamespace(), writeConcern, documentCodec, update)
                .filter(BsonDocument.parse('{str: "FOO"}'))
                .collation(caseInsensitiveCollation)

        when:
        def result = execute(operation, async)

        then:
        result == document

        where:
        async << [true, false]
    }
}
