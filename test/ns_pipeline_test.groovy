/* groovylint-disable LineLength */
@Library('ns_pipeline_library') _

final_summary = []
def helper
def executeNsPipelineFunction(Object... arguments) {
    argumentsList = []
    String fxnName
    def response = null

    fxnName = arguments[0]
    if (arguments.head() instanceof Map) {
        // Still to implement if required Ref: https://gist.github.com/int128/a136eb262b8f3f8decfda23542645b77
        println('Arguments ordering will be changed as last element is Map')
    }
    else {
        if (arguments.length > 1) {
            argumentsList = arguments[1..(arguments.length - 1)]
            println('   Arguments:' + arguments)
            println('   Function name is ' + fxnName)
            println('   argumentsList:' + argumentsList)
        }
    }

    try {
        if (argumentsList.size() == 0) {
            //println("Executing Function: ${fxnName}()")
            response = ns_pipeline."${fxnName}"()
        }
        else {
            //println("Executing Function: ${fxnName}(${argumentsList})")
            response = ns_pipeline."${fxnName}"(argumentsList)
        }
    } catch (Exception e) {
        println("Function: ${fxnName} failed with Exception: ${e}")
        currentBuild.result = 'FAILURE'
    }

    //println("Function output is: " + response)
    return response
}

def testFunction( testInputs, Object... fxnWithArguments ) {
    helper = new jenkinsTest.utils.helper()
    for (test in testInputs) {
        helper.setEnvIfRequired(test)
        fxnOutput = executeNsPipelineFunction(fxnWithArguments)
        status = helper.passOrFail(fxnOutput, test['expectedStatus'])
        final_summary = helper.logTestCase(fxnName, test['description'], status, final_summary)
        helper.resetEnvIfRequired(test)
    }
}

node('GCE-MISC') {
    stage('git checkout') {
        ns_pipeline.git_check_out('https://github.com/netSkope/ep-jenkins-pipeline.git', 'master', true, false, 120, false, 'github-https')
    }
    helper = new jenkinsTest.utils.helper()
    def workspace = "${env.WORKSPACE}"

    dir("${workspace}/test/") {
        def testCasesFiles = findFiles excludes: '', glob: '*Tests.json'
        testCasesFiles.each { test_file ->
            filename_string = test_file.toString()
            filename_without_extension = filename_string.take(filename_string.lastIndexOf('.')) //Remove extension
            stage("${filename_without_extension}") {
                def testGroup =  helper.jsonParse(readFile("${test_file}"))
                for (test in testGroup) {
                    fxnName = test.key
                    test_inputs = test.value
                    println("Executing Function:$test_file.${fxnName} with test_inputs:${test_inputs}")
                    testFunction(test_inputs, fxnName,"my_branch_name")
                }
            }
        }
    }
    helper.showSummary(final_summary)
}
