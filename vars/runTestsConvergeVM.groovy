
def call(String converge_stage_name,
         Boolean macos_build,
         String python_version,
         String macos_python_version,
         String distro_version,
         String distro_arch,
         String distro_name,
         String test_suite_name_slug) {

    def Integer returnStatus = 1;

    stage(converge_stage_name) {
        try {
            if ( macos_build ) {
                withEnv(["MACOS_PYTHON_VERSION=${macos_python_version}"]) {
                    sh label: 'Converge VM', script: '''
                    ssh-agent /bin/bash -xc 'ssh-add ~/.vagrant.d/insecure_private_key; bundle exec kitchen converge $TEST_SUITE-$TEST_PLATFORM; (exitcode=$?; echo "ExitCode: $exitcode"; exit $exitcode);'
                    '''
                }
            } else {
                sh label: 'Converge VM', script: '''
                ssh-agent /bin/bash -xc 'ssh-add ~/.ssh/kitchen.pem; bundle exec kitchen converge $TEST_SUITE-$TEST_PLATFORM; (exitcode=$?; echo "ExitCode: $exitcode"; exit $exitcode);'
                '''
            }
            sh label: 'Rename logs', script: """
            if [ -s ".kitchen/logs/${python_version}-${distro_name}-${distro_version}-${distro_arch}.log" ]; then
                mv ".kitchen/logs/${python_version}-${distro_name}-${distro_version}-${distro_arch}.log" ".kitchen/logs/${python_version}-${distro_name}-${distro_version}-${distro_arch}-${test_suite_name_slug}-converge.log"
            fi
            if [ -s ".kitchen/logs/kitchen.log" ]; then
                mv ".kitchen/logs/kitchen.log" ".kitchen/logs/kitchen-${test_suite_name_slug}-converge.log"
            fi
            """
            returnStatus = 0
        } finally {
            archiveArtifacts(
                artifacts: ".kitchen/logs/*-converge.log",
                allowEmptyArchive: true
            )
            return returnStatus
        }
    }
}
