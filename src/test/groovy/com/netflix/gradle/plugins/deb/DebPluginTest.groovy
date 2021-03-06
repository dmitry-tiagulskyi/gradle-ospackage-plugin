/*
 * Copyright 2011 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the 'License');
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an 'AS IS' BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.gradle.plugins.deb

import com.google.common.io.Files
import nebula.test.ProjectSpec
import nebula.test.dependencies.DependencyGraph
import nebula.test.dependencies.GradleDependencyGenerator
import org.apache.commons.io.FileUtils
import org.gradle.api.tasks.TaskExecutionException
import org.redline_rpm.header.Flags
import spock.lang.Issue
import spock.lang.Unroll

class DebPluginTest extends ProjectSpec {
    def 'minimal config'() {
        project.version = 1.0

        File appleFile = new File(project.buildDir, 'src/apple')
        Files.createParentDirs(appleFile)
        appleFile.text = 'apple'

        when:
        project.apply plugin: 'deb'

        Deb debTask = project.task([type: Deb], 'buildDeb', {
            release = '1'
            from(appleFile.getParentFile())
        })

        debTask.execute()

        then:
        File debFile = debTask.getArchivePath()
        debFile != null
        debFile.exists()
    }

//    public void alwaysRun(DefaultTask task ) {
//        assertTrue(this instanceof GroovyObject)
//        assertTrue(task.inputs instanceof GroovyObject)
//        task.inputs.metaClass.getHasSourceFiles = { true }
//
//        task.getMetaClass().setMetaMethod('getHasSourceFiles').
//        def emc = new ExpandoMetaClass(task.getClass(), false)
//        emc.say = { message -> message == "bad" ? false : sayClosure(message) }
//        emc.initialize()
//
//        specialClient.metaClass = emc
//        ExpandoMetaClass emc = new ExpandoMetaClass( Object, false )
//        emc.getHasSourceFiles = { true }
//        emc.initialize()
//
//        def obj = new groovy.util.Proxy().wrap( task.inputs )
//        obj.setMetaClass( emc )
//        task.inputs = obj
//    }

    def 'files'() {

        File srcDir = new File(projectDir, 'src')
        srcDir.mkdirs()
        FileUtils.writeStringToFile(new File(srcDir, 'apple'), 'apple')

        File noParentsDir = new File(projectDir, 'noParentsDir')
        noParentsDir.mkdirs()
        FileUtils.writeStringToFile(new File(noParentsDir, 'alone'), 'alone')

        project.apply plugin: 'deb'

        project.task([type: Deb], 'buildDeb', {
            destinationDir = project.file('build/tmp/DebPluginTest')
            destinationDir.mkdirs()

            packageName = 'bleah'
            version = '1.0'
            release = '1'
            arch 'amd64'
            maintainer = "Superman"
            uploaders = "Fantastic Four"
            permissionGroup = 'Development/Libraries'
            summary = 'Bleah blarg'
            packageDescription = 'Not a very interesting library.'
            license = 'Free'
            distribution = 'SuperSystem'
            vendor = 'Super Associates, LLC'
            url = 'http://www.example.com/'

            configurationFile '/etc/init.d/served'

            requires('blarg', '1.0', Flags.GREATER | Flags.EQUAL)
            requires('blech')

            into '/opt/bleah'
            from(srcDir)

            from(srcDir.toString() + '/main/groovy') {
                createDirectoryEntry true
                //fileType = CONFIG | NOREPLACE
            }

            from(noParentsDir) {
                addParentDirs = false
                into '/a/path/not/to/create'
            }

            link('/opt/bleah/banana', '/opt/bleah/apple')
        })

        when:
        project.tasks.buildDeb.execute()

        then:
        def scan = new Scanner(project.file('build/tmp/DebPluginTest/bleah_1.0-1_amd64.deb'))
        'bleah' == scan.getHeaderEntry('Package')
        'blarg (>= 1.0), blech' ==  scan.getHeaderEntry('Depends')
        'bleah' == scan.getHeaderEntry('Provides')
        'Bleah blarg\n Not a very interesting library.' == scan.getHeaderEntry('Description')
        'http://www.example.com/' == scan.getHeaderEntry('Homepage')
        'Superman' == scan.getHeaderEntry('Maintainer')
        'amd64' == scan.getHeaderEntry('Architecture')
        'optional' == scan.getHeaderEntry('Priority')

        scan.controlContents['./conffiles'].eachLine {
            '/etc/init.d/served' == it
        }
        
        def file = scan.getEntry('./a/path/not/to/create/alone')
        file.isFile()

        def dir = scan.getEntry('./opt/bleah/')
        dir.isDirectory()

        def file2 = scan.getEntry('./opt/bleah/apple')
        file2.isFile()

        def symlink = scan.getEntry('./opt/bleah/banana')
        symlink.isSymbolicLink()
    }

    def 'projectNameDefault'() {

        File srcDir = new File(projectDir, 'src')
        srcDir.mkdirs()
        FileUtils.writeStringToFile(new File(srcDir, 'apple'), 'apple')

        project.apply plugin: 'deb'

        Deb debTask = project.task('buildDeb', type: Deb) {
            packageName = 'project-name-default'
            from(srcDir)
        }

        when:
        debTask.execute()

        then:
        File debFile = debTask.getArchivePath()
        debFile != null

    }

    def 'permissionsDefaultToFileSystem'() {

        File srcDir = new File(projectDir, 'src')
        srcDir.mkdirs()
        def appleFile = new File(srcDir, 'apple.sh')
        FileUtils.writeStringToFile(appleFile, '#!/bin/bash\necho "Apples are yummy"')
        appleFile.setExecutable(true, false)
        appleFile.setReadable(true, false)
        appleFile.setWritable(false, false)
        appleFile.setWritable(true, true)

        def pearFile = new File(srcDir, 'pear')
        FileUtils.writeStringToFile(pearFile, 'pears are not apples')
        pearFile.setExecutable(false, false)
        pearFile.setReadable(true, false)
        pearFile.setWritable(false, false)
        pearFile.setWritable(true, true)

        project.apply plugin: 'deb'

        Deb debTask = project.task('buildDeb', type: Deb) {
            packageName = 'permissions-default-to-filesystem'
            from(srcDir)
        }

        when:
        debTask.execute()

        then:
        File debFile = debTask.getArchivePath()

        def scan = new Scanner(debFile)

        // FYI, rwxrwxrwx is 0777, i.e. 511 in decimal
        0755 == scan.getEntry('./apple.sh').mode
        0644 == scan.getEntry('./pear').mode

    }

    def 'generateScripts'() {
        project.version = 1.0

        File scriptDir = new File(projectDir, 'src')
        Files.createParentDirs(scriptDir)
        scriptDir.mkdir()

        File preinstallScript = new File(scriptDir, 'preinstall')
        preinstallScript.text = "#!/bin/bash\necho Preinstall"

        File postinstallScript = new File(scriptDir, 'postinstall')
        postinstallScript.text = "#!/bin/bash\necho Postinstall"

        File appleFile = new File(project.buildDir, 'src/apple')
        Files.createParentDirs(appleFile)
        appleFile.text = 'apple'

        project.apply plugin: 'deb'

        Deb debTask = (Deb) project.task([type: Deb], 'buildDeb', {
            packageName = 'generate-scripts'
            release '1'
            preInstall preinstallScript
            postInstall postinstallScript

            // SkipEmptySourceFilesTaskExecuter will prevent our task from running without a source
            from(appleFile.getParentFile())
        })

        when:
        debTask.execute()

        then:
        def scan = new Scanner(debTask.getArchivePath())
        scan.controlContents['./preinst'].contains("echo Preinstall")
        scan.controlContents['./postinst'].contains("echo Postinstall")
    }

    def 'generateScriptsThatAppendInstallUtil'() {
        project.version = 1.0

        File scriptDir = new File(project.buildDir, 'src')
        Files.createParentDirs(scriptDir)
        scriptDir.mkdir()

        File installScript = new File(scriptDir, 'install')
        installScript.text = "#!/bin/bash\necho Installing"

        File preinstallScript = new File(scriptDir, 'preinstall')
        preinstallScript.text = "echo Preinstall"

        File postinstallScript = new File(scriptDir, 'postinstall')
        postinstallScript.text = "echo Postinstall"

        File appleFile = new File(project.buildDir, 'src/apple')
        Files.createParentDirs(appleFile)
        appleFile.text = 'apple'

        project.apply plugin: 'deb'

        Deb debTask = (Deb) project.task([type: Deb], 'buildDeb', {
            packageName = 'generate-scripts-that-append-install-util'
            release '1'
            installUtils installScript
            preInstall preinstallScript
            postInstall postinstallScript

            // SkipEmptySourceFilesTaskExecuter will prevent our task from running without a source
            from(appleFile.getParentFile())
        })

        when:
        debTask.execute()

        then:
        def scan = new Scanner(debTask.getArchivePath())
        scan.controlContents['./preinst'].contains("echo Preinstall")
        scan.controlContents['./preinst'].contains("echo Installing")
        scan.controlContents['./postinst'].contains("echo Postinstall")
        scan.controlContents['./postinst'].contains("echo Installing")
    }
    def 'Packages correct content'() {
        given:
        String abcHtml1Content = 'Subfolder1'
        String abcHtml2Content = 'Subfolder2'

        File folderDir = new File(projectDir, 'folder')
        folderDir.mkdirs()
        File subfolder1Dir = new File(folderDir, 'subfolder1')
        subfolder1Dir.mkdirs()
        File subfolder2Dir = new File(folderDir, 'subfolder2')
        subfolder2Dir.mkdirs()
        FileUtils.writeStringToFile(new File(subfolder1Dir, 'abc.html'), abcHtml1Content)
        FileUtils.writeStringToFile(new File(subfolder2Dir, 'abc.html'), abcHtml2Content)

        project.apply plugin: 'deb'

        project.task([type: Deb], 'buildDeb', {
            destinationDir = project.file('build/tmp/DebPluginTest')
            destinationDir.mkdirs()

            packageName = 'bleah'
            version = '1.0'
            release = '1'

            from('folder') {
                include 'subfolder1/abc.html'
                include 'subfolder2/abc.html'
                into('folder')
            }
        })

        when:
        project.tasks.buildDeb.execute()

        then:
        def scan = new Scanner(project.file('build/tmp/DebPluginTest/bleah_1.0-1_all.deb'), project.file('build/tmp/extract'))
        def abcHtml1 = scan.getEntry('./folder/subfolder1/abc.html')
        abcHtml1.isFile()
        scan.dataContents[abcHtml1].text == abcHtml1Content

        def abcHtml2 = scan.getEntry('./folder/subfolder2/abc.html')
        abcHtml2.isFile()
        scan.dataContents[abcHtml2].text == abcHtml2Content
    }

    def 'Can apply filter for two files with a different name'() {
        given:
        String abcHtml1Content = 'Subfolder1 @version@'
        String abcHtml2Content = 'Subfolder2 @version@'

        File folderDir = new File(projectDir, 'folder')
        folderDir.mkdirs()
        File subfolder1Dir = new File(folderDir, 'subfolder1')
        subfolder1Dir.mkdirs()
        File subfolder2Dir = new File(folderDir, 'subfolder2')
        subfolder2Dir.mkdirs()
        FileUtils.writeStringToFile(new File(subfolder1Dir, 'abc1.html'), abcHtml1Content)
        FileUtils.writeStringToFile(new File(subfolder2Dir, 'abc2.html'), abcHtml2Content)

        project.apply plugin: 'deb'
        project.version = '1.0'

        project.task([type: Deb], 'buildDeb', {
            destinationDir = project.file('build/tmp/DebPluginTest')
            destinationDir.mkdirs()

            packageName = 'bleah'
            version = '1.0'
            release = '1'

            from('folder') {
                include 'subfolder1/abc1.html'
                include 'subfolder2/abc2.html'
                into('folder')
                filter(org.apache.tools.ant.filters.ReplaceTokens, tokens: [version: project.version])
            }
        })

        when:
        project.tasks.buildDeb.execute()

        then:
        def scan = new Scanner(project.file('build/tmp/DebPluginTest/bleah_1.0-1_all.deb'), project.file('build/tmp/extract'))
        def abcHtml1 = scan.getEntry('./folder/subfolder1/abc1.html')
        abcHtml1.isFile()
        scan.dataContents[abcHtml1].text == 'Subfolder1 1.0'

        def abcHtml2 = scan.getEntry('./folder/subfolder2/abc2.html')
        abcHtml2.isFile()
        scan.dataContents[abcHtml2].text == 'Subfolder2 1.0'
    }

    def 'Can apply filter for two files with the same name'() {
        given:
        String abcHtml1Content = 'Subfolder1 @version@'
        String abcHtml2Content = 'Subfolder2 @version@'

        File folderDir = new File(projectDir, 'folder')
        folderDir.mkdirs()
        File subfolder1Dir = new File(folderDir, 'subfolder1')
        subfolder1Dir.mkdirs()
        File subfolder2Dir = new File(folderDir, 'subfolder2')
        subfolder2Dir.mkdirs()
        FileUtils.writeStringToFile(new File(subfolder1Dir, 'abc.html'), abcHtml1Content)
        FileUtils.writeStringToFile(new File(subfolder2Dir, 'abc.html'), abcHtml2Content)

        project.apply plugin: 'deb'
        project.version = '1.0'

        project.task([type: Deb], 'buildDeb', {
            destinationDir = project.file('build/tmp/DebPluginTest')
            destinationDir.mkdirs()

            packageName = 'bleah'
            version = '1.0'
            release = '1'

            from('folder') {
                include 'subfolder1/abc.html'
                include 'subfolder2/abc.html'
                into('folder')
                filter(org.apache.tools.ant.filters.ReplaceTokens, tokens: [version: project.version])
            }
        })

        when:
        project.tasks.buildDeb.execute()

        then:
        def scan = new Scanner(project.file('build/tmp/DebPluginTest/bleah_1.0-1_all.deb'), project.file('build/tmp/extract'))
        def abcHtml1 = scan.getEntry('./folder/subfolder1/abc.html')
        abcHtml1.isFile()
        scan.dataContents[abcHtml1].text == 'Subfolder1 1.0'

        def abcHtml2 = scan.getEntry('./folder/subfolder2/abc.html')
        abcHtml2.isFile()
        scan.dataContents[abcHtml2].text == 'Subfolder2 1.0'
    }

    def 'Configures Multi-Arch control field'() {
        given:
        File srcDir = new File(projectDir, 'src')
        srcDir.mkdirs()
        FileUtils.writeStringToFile(new File(srcDir, 'apple'), 'apple')

        project.apply plugin: 'deb'
        project.version = '1.0'

        Deb debTask = project.task([type: Deb], 'buildDeb', {
            packageName = 'configures-multi-arch-control-field'
            multiArch = FOREIGN
        })
        debTask.from(srcDir)

        when:
        debTask.execute()

        then:
        File debFile = debTask.getArchivePath()
        def ant = new AntBuilder()
        ant.copy(file: debTask.getArchivePath(), toFile: '/tmp/foo.deb')
        def scan = new Scanner(debFile)
        'foreign' == scan.getHeaderEntry('Multi-Arch')
    }

    def 'Disallows Multi-Arch: same for Architecture: all'() {
        given:
        File srcDir = new File(projectDir, 'src')
        srcDir.mkdirs()
        FileUtils.writeStringToFile(new File(srcDir, 'apple'), 'apple')

        project.apply plugin: 'deb'
        project.version = '1.0'

        Deb debTask = project.task([type: Deb], 'buildDeb', {
            packageName = 'disallows-multi-arch-same-for-architecture-all'
            multiArch = SAME
        })
        debTask.from(srcDir)

        when:
        debTask.execute()

        then:
        TaskExecutionException ex = thrown()
        IllegalArgumentException iex = ex.cause
        'Deb packages with Architecture: all cannot declare Multi-Arch: same' == iex.message
    }

    def 'Configures Conflicts control field'() {
        given:
        File srcDir = new File(projectDir, 'src')
        srcDir.mkdirs()
        FileUtils.writeStringToFile(new File(srcDir, 'apple'), 'apple')

        project.apply plugin: 'deb'
        project.version = '1.0'

        Deb debTask = project.task([type: Deb], 'buildDeb', {
            packageName = 'configures-conflicts-control-field'
            conflicts 'foo'
            conflicts('bar', '1.0', Flags.GREATER | Flags.EQUAL)
            conflicts('baz', '2.0', Flags.LESS | Flags.EQUAL)
        })
        debTask.from(srcDir)

        when:
        debTask.execute()

        then:
        File debFile = debTask.getArchivePath()
        def ant = new AntBuilder()
        ant.copy(file: debTask.getArchivePath(), toFile: '/tmp/foo.deb')
        def scan = new Scanner(debFile)
        'foo, bar (>= 1.0), baz (<= 2.0)' ==  scan.getHeaderEntry('Conflicts')
    }

    def 'Configures Recommends control field'() {
        given:
        File srcDir = new File(projectDir, 'src')
        srcDir.mkdirs()
        FileUtils.writeStringToFile(new File(srcDir, 'apple'), 'apple')

        project.apply plugin: 'deb'
        project.version = '1.0'

        Deb debTask = project.task([type: Deb], 'buildDeb', {
            packageName = 'configures-recommends-control-field'
            recommends 'foo'
            recommends('bar', '1.0', Flags.GREATER | Flags.EQUAL)
            recommends('baz', '2.0', Flags.LESS | Flags.EQUAL)
        })
        debTask.from(srcDir)

        when:
        debTask.execute()

        then:
        File debFile = debTask.getArchivePath()
        def ant = new AntBuilder()
        ant.copy(file: debTask.getArchivePath(), toFile: '/tmp/foo.deb')
        def scan = new Scanner(debFile)
        'foo, bar (>= 1.0), baz (<= 2.0)' ==  scan.getHeaderEntry('Recommends')
    }

    def 'Configures Suggests control field'() {
        given:
        File srcDir = new File(projectDir, 'src')
        srcDir.mkdirs()
        FileUtils.writeStringToFile(new File(srcDir, 'apple'), 'apple')

        project.apply plugin: 'deb'
        project.version = '1.0'

        Deb debTask = project.task([type: Deb], 'buildDeb', {
            packageName = 'configures-suggests-control-field'
            suggests 'foo'
            suggests('bar', '1.0', Flags.GREATER | Flags.EQUAL)
            suggests('baz', '2.0', Flags.LESS | Flags.EQUAL)
        })
        debTask.from(srcDir)

        when:
        debTask.execute()

        then:
        File debFile = debTask.getArchivePath()
        def ant = new AntBuilder()
        ant.copy(file: debTask.getArchivePath(), toFile: '/tmp/foo.deb')
        def scan = new Scanner(debFile)
        'foo, bar (>= 1.0), baz (<= 2.0)' ==  scan.getHeaderEntry('Suggests')
    }

    def 'Configures Enhances control field'() {
        given:
        File srcDir = new File(projectDir, 'src')
        srcDir.mkdirs()
        FileUtils.writeStringToFile(new File(srcDir, 'apple'), 'apple')

        project.apply plugin: 'deb'
        project.version = '1.0'

        Deb debTask = project.task([type: Deb], 'buildDeb', {
            packageName = 'configures-enhances-control-field'
            enhances 'foo'
            enhances('bar', '1.0', Flags.GREATER | Flags.EQUAL)
            enhances('baz', '2.0', Flags.LESS | Flags.EQUAL)
        })
        debTask.from(srcDir)

        when:
        debTask.execute()

        then:
        File debFile = debTask.getArchivePath()
        def ant = new AntBuilder()
        ant.copy(file: debTask.getArchivePath(), toFile: '/tmp/foo.deb')
        def scan = new Scanner(debFile)
        'foo, bar (>= 1.0), baz (<= 2.0)' ==  scan.getHeaderEntry('Enhances')
    }

    def 'Configures Pre-Depends control field'() {
        given:
        File srcDir = new File(projectDir, 'src')
        srcDir.mkdirs()
        FileUtils.writeStringToFile(new File(srcDir, 'apple'), 'apple')

        project.apply plugin: 'deb'
        project.version = '1.0'

        Deb debTask = project.task([type: Deb], 'buildDeb', {
            packageName = 'configures-pre-depends-control-field'
            preDepends 'foo'
            preDepends('bar', '1.0', Flags.GREATER | Flags.EQUAL)
            preDepends('baz', '2.0', Flags.LESS | Flags.EQUAL)
        })
        debTask.from(srcDir)

        when:
        debTask.execute()

        then:
        File debFile = debTask.getArchivePath()
        def ant = new AntBuilder()
        ant.copy(file: debTask.getArchivePath(), toFile: '/tmp/foo.deb')
        def scan = new Scanner(debFile)
        'foo, bar (>= 1.0), baz (<= 2.0)' ==  scan.getHeaderEntry('Pre-Depends')
    }

    def 'Configures Breaks control field'() {
        given:
        File srcDir = new File(projectDir, 'src')
        srcDir.mkdirs()
        FileUtils.writeStringToFile(new File(srcDir, 'apple'), 'apple')

        project.apply plugin: 'deb'
        project.version = '1.0'

        Deb debTask = project.task([type: Deb], 'buildDeb', {
            packageName = 'configures-breaks-control-field'
            breaks 'foo'
            breaks('bar', '1.0', Flags.GREATER | Flags.EQUAL)
            breaks('baz', '2.0', Flags.LESS | Flags.EQUAL)
        })
        debTask.from(srcDir)

        when:
        debTask.execute()

        then:
        File debFile = debTask.getArchivePath()
        def ant = new AntBuilder()
        ant.copy(file: debTask.getArchivePath(), toFile: '/tmp/foo.deb')
        def scan = new Scanner(debFile)
        'foo, bar (>= 1.0), baz (<= 2.0)' ==  scan.getHeaderEntry('Breaks')
    }

    def 'Configures Replaces control field'() {
        given:
        File srcDir = new File(projectDir, 'src')
        srcDir.mkdirs()
        FileUtils.writeStringToFile(new File(srcDir, 'apple'), 'apple')

        project.apply plugin: 'deb'
        project.version = '1.0'

        Deb debTask = project.task([type: Deb], 'buildDeb', {
            packageName = 'configures-replaces-control-field'
            replaces 'foo'
            replaces('bar', '1.0', Flags.GREATER | Flags.EQUAL)
            replaces('baz', '2.0', Flags.LESS | Flags.EQUAL)
        })
        debTask.from(srcDir)

        when:
        debTask.execute()

        then:
        File debFile = debTask.getArchivePath()
        def ant = new AntBuilder()
        ant.copy(file: debTask.getArchivePath(), toFile: '/tmp/foo.deb')
        def scan = new Scanner(debFile)
        'foo, bar (>= 1.0), baz (<= 2.0)' ==  scan.getHeaderEntry('Replaces')
    }


    @Issue("https://github.com/nebula-plugins/gradle-ospackage-plugin/issues/48")
    def "Does not throw UnsupportedOperationException when copying external artifact with createDirectoryEntry option"() {
        given:
        String testCoordinates = 'com.netflix.nebula:a:1.0.0'
        DependencyGraph graph = new DependencyGraph([testCoordinates])
        File reposRootDir = new File(project.buildDir, 'repos')
        GradleDependencyGenerator generator = new GradleDependencyGenerator(graph, reposRootDir.absolutePath)
        generator.generateTestMavenRepo()

        project.apply plugin: 'deb'

        project.configurations {
            myConf
        }

        project.dependencies {
            myConf testCoordinates
        }

        project.repositories {
            maven {
                url {
                    "file://$reposRootDir/mavenrepo"
                }
            }
        }

        Deb debTask = project.task('buildDeb', type: Deb) {
            packageName = 'does-not-throw-unsupportedoperationexception-when-copying-external-artifact-with-createdirectoryentry-option'
            from(project.configurations.myConf) {
                createDirectoryEntry = true
                into('root/lib')
            }
        }

        when:
        debTask.execute()

        then:
        noExceptionThrown()
    }

    @Unroll
    def "Handles release flag in version header if value '#providedRelease' assigned to release property"() {
        given:
        project.apply plugin: 'deb'

        Deb debTask = project.task('buildDeb', type: Deb) {
            packageName = 'my-package'
            version = '1.0.0'
            release = providedRelease
        }

        when:
        debTask.execute()

        then:
        def scan = new Scanner(debTask.archivePath)
        scan.getHeaderEntry('Version') == versionHeader

        where:
        providedRelease | versionHeader
        '0'             | '1.0.0-0'
        '1'             | '1.0.0-1'
        ''              | '1.0.0'
        null            | '1.0.0'
    }

    def 'Handle version when using epoch'() {
        given:
        project.apply plugin: 'deb'

        Deb debTask = project.task('buildDeb', type: Deb) {
            packageName = 'my-package'
            version = '42.1.0'
            epoch = 1
        }

        when:
        debTask.execute()

        then:
        def scan = new Scanner(debTask.archivePath)
        scan.getHeaderEntry('Version') == '1:42.1.0'
    }
}
