@Library('ns_pipeline_library') _

node {
    stage('Test library function') {
        /*
            Description - Check default artifactory
            Input       - None
            E output    - artifactory
        */
        def default_artifactory
        try {
            default_artifactory = ns_pipeline.get_default_artifactory()
        }
        catch (e){
            currentBuild.result = 'FAILURE'
            default_artifactory = ''
        }
        if ( default_artifactory == "artifactory") {
            echo "Passed"
        }
        //def is_develop_job_env_input = ["develop_env1": "env1","develop_env2":"env2"]
        //run_function("is_develop_job",is_develop_job_env_input,"","")
        def repo_maps = ns_pipeline.get_repo_map()
        echo "repo maps: ${repo_maps}"
    }
}
