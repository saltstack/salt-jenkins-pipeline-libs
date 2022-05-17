
def call(String create_stage_name,
         Boolean macos_build,
         Boolean use_spot_instances,
         String python_version,
         String macos_python_version,
         String vagrant_box_details_stage_name,
         String distro_version,
         String distro_arch,
         String distro_name,
         String test_suite_name_slug) {

    stage(create_stage_name) {
        if ( macos_build ) {
            stage(vagrant_box_details_stage_name) {
                sh '''
                bundle exec kitchen diagnose $TEST_SUITE-$TEST_PLATFORM | grep 'box'; (exitcode=$?; echo "ExitCode: $exitcode"; exit $exitcode);
                '''
            }
            try {
                withEnv(["MACOS_PYTHON_VERSION=${macos_python_version}"]) {
                    sh """
                    # wait at most 120 minutes for the other job to finish downloading/creating the vagrant box
                    while find /tmp/lock_${distro_version}_${distro_arch} -mmin -120 | grep -q /tmp/lock_${distro_version}_${distro_arch}
                    do
                        echo 'vm creation locked, sleeping 120 seconds'
                        sleep 120
                    done
                    touch /tmp/lock_${distro_version}_${distro_arch}
                    """
                    sh '''
                    bundle exec kitchen create $TEST_SUITE-$TEST_PLATFORM; (exitcode=$?; echo "ExitCode: $exitcode"; exit $exitcode);
                    '''
                    sh """
                    if [ -s ".kitchen/logs/${python_version}-${distro_name}-${distro_version}-${distro_arch}.log" ]; then
                        mv ".kitchen/logs/${python_version}-${distro_name}-${distro_version}-${distro_arch}.log" ".kitchen/logs/${python_version}-${distro_name}-${distro_version}-${distro_arch}-${test_suite_name_slug}-create.log"
                    fi
                    if [ -s ".kitchen/logs/kitchen.log" ]; then
                        mv ".kitchen/logs/kitchen.log" ".kitchen/logs/kitchen-${test_suite_name_slug}-create.log"
                    fi
                    """
                }
            } finally {
                sh """
                rm -f /tmp/lock_${distro_version}_${distro_arch}
                """
            }
        } else {
            retry(3) {
                if ( use_spot_instances ) {
                    sh '''
                    cp -f ~/workspace/spot.yml .kitchen.local.yml
                    t=$(shuf -i 30-150 -n 1); echo "Sleeping $t seconds"; sleep $t
                    bundle exec kitchen create $TEST_SUITE-$TEST_PLATFORM || (bundle exec kitchen destroy $TEST_SUITE-$TEST_PLATFORM; rm .kitchen.local.yml; bundle exec kitchen create $TEST_SUITE-$TEST_PLATFORM); (exitcode=$?; echo "ExitCode: $exitcode"; exit $exitcode);
                    '''
                } else {
                    sh '''
                    t=$(shuf -i 30-150 -n 1); echo "Sleeping $t seconds"; sleep $t
                    bundle exec kitchen create $TEST_SUITE-$TEST_PLATFORM; (exitcode=$?; echo "ExitCode: $exitcode"; exit $exitcode);
                    '''
                }
                sh """
                if [ -s ".kitchen/logs/${python_version}-${distro_name}-${distro_version}-${distro_arch}.log" ]; then
                    mv ".kitchen/logs/${python_version}-${distro_name}-${distro_version}-${distro_arch}.log" ".kitchen/logs/${python_version}-${distro_name}-${distro_version}-${distro_arch}-${test_suite_name_slug}-create.log"
                fi
                if [ -s ".kitchen/logs/kitchen.log" ]; then
                    mv ".kitchen/logs/kitchen.log" ".kitchen/logs/kitchen-${test_suite_name_slug}-create.log"
                fi
                """
            }
            try {
                sh '''
                bundle exec kitchen diagnose $TEST_SUITE-$TEST_PLATFORM > kitchen-diagnose-info.txt
                grep 'image_id:' kitchen-diagnose-info.txt
                grep 'instance_type:' -A5 kitchen-diagnose-info.txt
                '''
            } catch (Exception kitchen_diagnose_error) {
                println "Failed to get the kitchen diagnose information: ${kitchen_diagnose_error}"
            } finally {
                sh '''
                rm -f kitchen-diagnose-info.txt
                rm -f .kitchen.local.yml
                '''
            }
        }
    }
}
