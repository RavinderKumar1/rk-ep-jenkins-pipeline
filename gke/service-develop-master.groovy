@Library('ns_pipeline_library') _

node ('gcp-slave'){

    stage('git checkout') {
        git branch: 'main',
                url: 'https://github.com/RavinderKumar1/rk-ep-jenkins-pipeline.git'
    }

    stage('Test library function') {
        /*
            Description - Test type1 : simple, Check default artifactory
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
        /*
             Description - Test type2 : with env var, Override default artifactory
             Input       - DEFAULT_ARTIFACTORY:OVERRIDDEN_ARTIFACTORY
             E output    - OVERRIDDEN_ARTIFACTORY
        */
        env.setProperty("DEFAULT_ARTIFACTORY", 'OVERRIDDEN_ARTIFACTORY')
        def _overridden_rtf = ns_pipeline.get_default_artifactory()
        echo "overridden artifactory: ${_overridden_rtf}"


        properties([
                parameters([
                        string(name: 'color', defaultValue: 'blue', description: 'The build color')
                ])
        ])
        print "param value: " + this.params.color
//        def repo_maps = ns_pipeline.get_repo_map()
//        echo "repo maps: ${repo_maps}"
    }
}
