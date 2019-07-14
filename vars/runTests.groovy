def call(String checkout_directory,
         String stage_name,
         env_array,
         Integer chunk_timeout) {

    def stage_slug = stage_name.replace('#', '').replace(' ', '-').toLowerCase()
    def checkout_dir = "${checkout_directory}-${stage_slug}"
    sh "cp -Rp $checkout_directory $checkout_dir"
    dir(checkout_dir) {
        withEnv(env_array) {
            stage(stage_name) {
                stage('Create VM') {
                    retry(3) {
                        sh '''
                        t=$(shuf -i 1-30 -n 1); echo "Sleeping $t seconds"; sleep $t
                        bundle exec kitchen create $TEST_SUITE-$TEST_PLATFORM; echo "ExitCode: $?";
                        '''
                    }
                }
                sshagent(credentials: ['jenkins-testing-ssh-key']) {
                    sh 'ssh-add ~/.ssh/kitchen.pem'
                    try {
                        timeout(time: chunk_timeout, unit: 'HOURS') {
                            stage('Converge VM') {
                                sh 'bundle exec kitchen converge $TEST_SUITE-$TEST_PLATFORM; echo "ExitCode: $?";'
                            }
                            stage('Run Tests') {
                                withEnv(["DONT_DOWNLOAD_ARTEFACTS=1"]) {
                                    sh 'bundle exec kitchen verify $TEST_SUITE-$TEST_PLATFORM; echo "ExitCode: $?";'
                                }
                            }
                        }
                    } finally {
                        try {
                            stage('Download Artefacts') {
                                withEnv(["ONLY_DOWNLOAD_ARTEFACTS=1"]){
                                    sh '''
                                    bundle exec kitchen verify $TEST_SUITE-$TEST_PLATFORM || exit 0
                                    '''
                                }
                            }
                            junit 'artifacts/xml-unittests-output/*.xml'
                        } finally {
                            stage('Cleanup') {
                                sh '''
                                bundle exec kitchen destroy $TEST_SUITE-$TEST_PLATFORM; echo "ExitCode: $?";
                                '''
                            }
                            uploadCodeCoverage()
                        }
                    }
                }
            }
        }
    }
    archiveArtifacts allowEmptyArchive: true, artifacts: "${checkout_dir}/artifacts/*,${checkout_dir}/artifacts/**/*,${checkout_dir}/.kitchen/logs/kitchen.log"
}
// vim: ft=groovy ts=4 sts=4 et
