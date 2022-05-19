
def call(String converge_stage_name,
         Boolean macos_build,
         String python_version,
         String macos_python_version,
         String distro_version,
         String distro_arch,
         String distro_name,
         String test_suite_name_slug) {

    stage(converge_stage_name) {
        if ( macos_build ) {
            withEnv(["MACOS_PYTHON_VERSION=${macos_python_version}"]) {
                sh '''
                ssh-agent /bin/bash -xc 'ssh-add ~/.vagrant.d/insecure_private_key; bundle exec kitchen converge $TEST_SUITE-$TEST_PLATFORM; (exitcode=$?; echo "ExitCode: $exitcode"; exit $exitcode);'
                '''
            }
        } else {
            sh '''
            ssh-agent /bin/bash -xc 'ssh-add ~/.ssh/kitchen.pem; bundle exec kitchen converge $TEST_SUITE-$TEST_PLATFORM; (exitcode=$?; echo "ExitCode: $exitcode"; exit $exitcode);'
            '''
        }
        sh """
        if [ -s ".kitchen/logs/${python_version}-${distro_name}-${distro_version}-${distro_arch}.log" ]; then
            mv ".kitchen/logs/${python_version}-${distro_name}-${distro_version}-${distro_arch}.log" ".kitchen/logs/${python_version}-${distro_name}-${distro_version}-${distro_arch}-${test_suite_name_slug}-converge.log"
        fi
        if [ -s ".kitchen/logs/kitchen.log" ]; then
            mv ".kitchen/logs/kitchen.log" ".kitchen/logs/kitchen-${test_suite_name_slug}-converge.log"
        fi
        """
    }
}
