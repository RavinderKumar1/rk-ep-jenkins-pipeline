@Library('ns_pipeline_library')_
//package unit.library

import net.sf.json.JSONObject
import net.sf.json.JSONArray
import org.junit.*
import com.lesfurets.jenkins.unit.*
import static groovy.test.GroovyAssert.*
import static com.lesfurets.jenkins.unit.global.lib.LibraryConfiguration.library
import static com.lesfurets.jenkins.unit.global.lib.ProjectSource.projectSource
import groovy.json.JsonSlurper
import groovy.json.*

class NsPipelineTest extends BasePipelineTest {
    def nsPipeline

    @Before
    void setUp() {
        super.setUp()
        // load ns_pipeline library
        println("loading ns_pipeline groovy script")
        nsPipeline = loadScript("vars/ns_pipeline.groovy")
    }

    @Test
    void "Test get_repo_map"(){
        /*
            Description - Test Get Repo name - URL mapping
            Input       - WORKSPACE
            E output    - Return GIT URL of the service
        */
        addParam('WORKSPACE', 'MOCK_WORKSPACE')
        println("Calling get repo map from test function")
        printCallStack()
        ns_pipeline.get_repo_map()
        //assertEquals "output:", 'https://api.github.com/repos/netSkope/service/', output
    }
}