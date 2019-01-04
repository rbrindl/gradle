/*
 * Copyright 2010 the original author or authors.
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
package org.gradle.api.internal.tasks

import org.gradle.api.file.FileCollection
import org.gradle.api.internal.TaskInternal
import org.gradle.api.internal.TaskOutputsInternal
import org.gradle.api.internal.file.FileResolver
import org.gradle.api.internal.file.FileTreeInternal
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.api.internal.tasks.properties.DefaultPropertyWalker
import org.gradle.api.internal.tasks.properties.DefaultTypePropertyMetadataStore
import org.gradle.api.internal.tasks.properties.GetInputFilesVisitor
import org.gradle.api.internal.tasks.properties.GetInputPropertiesVisitor
import org.gradle.api.internal.tasks.properties.PropertyVisitor
import org.gradle.cache.internal.TestCrossBuildInMemoryCacheFactory
import org.gradle.util.UsesNativeServices
import spock.lang.Issue
import spock.lang.Specification
import spock.lang.Unroll

import java.util.concurrent.Callable

@UsesNativeServices
class DefaultTaskInputsTest extends Specification {
    private final File treeFile = new File('tree')
    private final tree = [getFiles: { [treeFile] as Set}] as FileTreeInternal
    private final FileResolver resolver = [
            resolve: { new File((String) it) },
            resolveFilesAsTree: {tree}
    ] as FileResolver

    private def taskStatusNagger = Stub(TaskMutator) {
        mutate(_, _) >> { String method, Object action ->
            if (action instanceof Runnable) {
                action.run()
            } else if (action instanceof Callable) {
                return action.call()
            }
        }
    }
    def project = Stub(ProjectInternal) {
        getFileFileResolver() >> resolver
    }
    def task = Mock(TaskInternal) {
        getName() >> "task"
        toString() >> "task 'task'"
        getProject() >> project
        getInputs() >> { inputs }
        getOutputs() >> Stub(TaskOutputsInternal)
        getDestroyables() >> Stub(TaskDestroyablesInternal)
        getLocalState() >> Stub(TaskLocalStateInternal)
    }
    def walker = new DefaultPropertyWalker(new DefaultTypePropertyMetadataStore([], new TestCrossBuildInMemoryCacheFactory()))
    private final DefaultTaskInputs inputs = new DefaultTaskInputs(task, taskStatusNagger, walker, new DefaultPropertySpecFactory(task, resolver))

    def "default values"() {
        expect:
        inputFiles().empty
        inputProperties().isEmpty()
        !inputs.hasInputs
        !inputs.hasSourceFiles
        inputs.sourceFiles.empty
    }

    def "can register input file"() {
        when: inputs.file("a")
        then:
        inputFiles() == [new File('a')]
        inputFileProperties().propertyName == ['$1']
        inputFileProperties().propertyFiles*.files.flatten() == [new File("a")]
    }

    def "can register input file with property name"() {
        when: inputs.file("a").withPropertyName("prop")
        then:
        inputFiles() == [new File('a')]
        inputFileProperties().propertyName == ['prop']
        inputFileProperties().propertyFiles*.files.flatten() == [new File("a")]
    }

    def "can register input files"() {
        when:
        inputs.files("a", "b")
        then:
        inputFiles() == [new File("a"), new File("b")]
        inputFileProperties().propertyName == ['$1']
        inputFileProperties().propertyFiles*.files.flatten() == [new File("a"), new File("b")]
    }

    def "can register input files with property name"() {
        when: inputs.files("a", "b").withPropertyName("prop")
        then:
        inputFiles() == [new File("a"), new File("b")]
        inputFileProperties().propertyName == ['prop']
        inputFileProperties().propertyFiles*.files.flatten() == [new File("a"), new File("b")]
    }

    def "can register input dir"() {
        when: inputs.dir("a")
        then:
        inputFiles() == [treeFile]
        inputFileProperties().propertyName == ['$1']
        inputFileProperties().propertyFiles*.files.flatten() == [treeFile]
    }

    def "can register input dir with property name"() {
        when: inputs.dir("a").withPropertyName("prop")
        then:
        inputFiles() == [treeFile]
        inputFileProperties().propertyName == ['prop']
        inputFileProperties().propertyFiles*.files.flatten() == [treeFile]
    }

    def "cannot register input file with same property name"() {
        inputs.file("a").withPropertyName("alma")
        inputs.file("b").withPropertyName("alma")
        when:
        inputFileProperties()
        then:
        def ex = thrown IllegalArgumentException
        ex.message == "Multiple input file properties with name 'alma'"
    }

    def canRegisterInputProperty() {
        when:
        inputs.property('a', 'value')

        then:
        inputProperties() == [a: 'value']
    }

    def canRegisterInputPropertyUsingAClosure() {
        when:
        inputs.property('a', { 'value' })

        then:
        inputProperties() == [a: 'value']
    }

    def canRegisterInputPropertyUsingACallable() {
        when:
        inputs.property('a', { 'value' } as Callable)

        then:
        inputProperties() == [a: 'value']
    }

    def canRegisterInputPropertyUsingAFileCollection() {
        def files = [new File('file')] as Set

        when:
        inputs.property('a', [getFiles: { files }] as FileCollection)

        then:
        inputProperties() == [a: files]
    }

    def inputPropertyCanBeNestedCallableAndClosure() {
        def files = [new File('file')] as Set
        def fileCollection = [getFiles: { files }] as FileCollection
        def callable = {fileCollection} as Callable

        when:
        inputs.property('a', { callable })

        then:
        inputProperties() == [a: files]
    }

    def "GString input property values are evaluated to avoid serialization issues"() {
        when:
        inputs.property('a', { "hey ${new NotSerializable()}" })

        then:
        inputProperties() == [a: "hey Joe"]
        String.is inputProperties().a.class
    }

    class NotSerializable {
        String toString() { "Joe" }
    }

    def "can register source files"() {
        when: inputs.files("a", "b").withPropertyName("prop")
        then:
        inputs.hasInputs
        !inputs.hasSourceFiles

        when: inputs.files(["s1", "s2"]).skipWhenEmpty()
        then:
        inputs.hasSourceFiles
        inputFiles() == [new File("a"), new File("b"), new File("s1"), new File("s2")]
        inputs.sourceFiles.files.toList() == [new File("s1"), new File("s2")]
        inputFileProperties().propertyName == ['$1', 'prop']
        inputFileProperties().propertyFiles*.toList() == [[new File("s1"), new File("s2")], [new File("a"), new File("b")]]
    }

    def canRegisterSourceFile() {
        when:
        inputs.file('file').skipWhenEmpty()

        then:
        inputs.sourceFiles.files == ([new File('file')] as Set)
    }

    def canRegisterSourceFiles() {
        when:
        inputs.files('file', 'file2').skipWhenEmpty()

        then:
        inputs.sourceFiles.files == ([new File('file'), new File('file2')] as Set)
    }

    def canRegisterSourceDir() {
        when:
        inputs.dir('dir').skipWhenEmpty()

        then:
        inputs.sourceFiles.files == [treeFile] as Set
    }

    def sourceFilesAreAlsoInputFiles() {
        when:
        inputs.file('file').skipWhenEmpty()

        then:
        inputs.sourceFiles.files == ([new File('file')] as Set)
        inputFiles() == [new File('file')]
    }

    def hasInputsWhenEmptyInputFilesRegistered() {
        when:
        inputs.files([])

        then:
        inputs.hasInputs
        !inputs.hasSourceFiles
    }

    def hasInputsWhenNonEmptyInputFilesRegistered() {
        when:
        inputs.files('a')

        then:
        inputs.hasInputs
        !inputs.hasSourceFiles
    }

    def hasInputsWhenInputPropertyRegistered() {
        when:
        inputs.property('a', 'value')

        then:
        inputs.hasInputs
        !inputs.hasSourceFiles
    }

    def hasInputsWhenEmptySourceFilesRegistered() {
        when:
        inputs.files([]).skipWhenEmpty()

        then:
        inputs.hasInputs
        inputs.hasSourceFiles
    }

    def hasInputsWhenSourceFilesRegistered() {
        when:
        inputs.file('a').skipWhenEmpty()

        then:
        inputs.hasInputs
        inputs.hasSourceFiles
    }

    @Issue("https://github.com/gradle/gradle/issues/4085")
    @Unroll
    def "can register more unnamed properties with method #method after properties have been queried"() {
        inputs."$method"("input-1")
        // Trigger naming properties
        inputs.hasSourceFiles
        inputs."$method"("input-2")
        def names = []

        when:
        inputs.visitRegisteredProperties(new PropertyVisitor.Adapter() {
            @Override
            void visitInputFileProperty(TaskInputFilePropertySpec property) {
                names += property.propertyName
            }
        })
        then:
        names == ['$1', '$2']

        where:
        method << ["file", "dir", "files"]
    }

    def inputProperties() {
        def visitor = new GetInputPropertiesVisitor("test")
        TaskPropertyUtils.visitProperties(walker, task, visitor)
        return visitor.propertyValuesFactory.create()
    }

    def inputFileProperties() {
        GetInputFilesVisitor visitor = new GetInputFilesVisitor()
        TaskPropertyUtils.visitProperties(walker, task, visitor)
        return visitor.getFileProperties()
    }

    def inputFiles() {
        inputFileProperties()*.propertyFiles*.files.flatten().toList().sort()
    }
}
