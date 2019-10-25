def call(String distro_name,
         String distro_version,
         String python_version,
         String salt_target_branch,
         String golden_images_branch,
         String nox_env_name,
         String nox_passthrough_opts,
         Integer testrun_timeout,
         Map optional) {

    def Boolean run_full = optional.get('run_full', true)
    def Boolean macos_build = optional.get('macos_build', false)
    def Boolean use_spot_instances = optional.get('use_spot_instances', false)
    def String rbenv_version = optional.get('rbenv_version', '2.6.3')
    def String jenkins_slave_label = optional.get('jenkins_slave_label', 'kitchen-slave')
    def String notify_slack_channel = optional.get('notify_slack_channel', '#jenkins-prod-pr')
    def String kitchen_driver_file = optional.get('kitchen_driver_file', '/var/jenkins/workspace/driver.yml')
    def String kitchen_verifier_file = optional.get('kitchen_verifier_file', '/var/jenkins/workspace/nox-verifier.yml')
    def String kitchen_platforms_file = optional.get('kitchen_platforms_file', '/var/jenkins/workspace/nox-platforms.yml')

    // Define a global pipeline timeout. This is the test run timeout with one(1) additional
    // hour to allow for artifacts to be downloaded, if possible.
    def global_timeout = testrun_timeout + 1

    echo "Distro: ${distro_name}"
    echo "Distro Version: ${distro_version}"
    echo "Python Version: ${python_version}"
    echo "Salt Target Branch: ${salt_target_branch}"
    echo "Golden Images Branch: ${golden_images_branch}"
    echo "Nox Env Name: ${nox_env_name}"
    echo "Nox Passthrough Opts: ${nox_passthrough_opts}"
    echo "Test run timeout: ${testrun_timeout} Hours"
    echo "Global Timeout: ${global_timeout} Hours"
    echo "Use SPOT instances: ${use_spot_instances}"
    echo "Jenkins Slave Label: ${jenkins_slave_label}"
    echo "Noxtify Slack Channel: ${notify_slack_channel}"
    echo "Kitchen Driver File: ${kitchen_driver_file}"

    wrappedNode(jenkins_slave_label, global_timeout, notify_slack_channel) {
        withEnv([
            "SALT_KITCHEN_PLATFORMS=${kitchen_platforms_file}",
            "SALT_KITCHEN_VERIFIER=${kitchen_verifier_file}",
            "SALT_KITCHEN_DRIVER=${kitchen_driver_file}",
            "NOX_ENV_NAME=${nox_env_name.toLowerCase()}",
            'NOX_ENABLE_FROM_FILENAMES=true',
            "NOX_PASSTHROUGH_OPTS=${nox_passthrough_opts}",
            "SALT_TARGET_BRANCH=${salt_target_branch}",
            "GOLDEN_IMAGES_CI_BRANCH=${golden_images_branch}",
            "CODECOV_FLAGS=${distro_name}${distro_version},${python_version},${nox_env_name.toLowerCase().split('-').join(',')}",
            'PATH=~/.rbenv/shims:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin',
            "RBENV_VERSION=${rbenv_version}",
            "TEST_SUITE=${python_version}",
            "TEST_PLATFORM=${distro_name}-${distro_version}",
            "FORCE_FULL=${run_full}",
        ]) {

            if ( macos_build ) {
                // Cleanup old VMs
                stage('VM Cleanup') {
                    sh '''
                    for i in `prlctl list -aij|jq -r '.[]|select((.Uptime|tonumber > 86400) and (.State == "running"))|.ID'`
                    do
                        prlctl stop $i --kill
                    done
                    # don't delete vm's that haven't started yet ((.State == "stopped") and (.Uptime == "0"))
                    for i in `prlctl list -aij|jq -r '.[]|select((.Uptime|tonumber > 0) and (.State != "running"))|.ID'`
                    do
                        prlctl delete $i
                    done
                    '''
                }
            }

            // Checkout the repo
            stage('Clone') {
                cleanWs notFailBuild: true
                checkout scm
                sh 'git fetch --no-tags https://github.com/saltstack/salt.git +refs/heads/${SALT_TARGET_BRANCH}:refs/remotes/origin/${SALT_TARGET_BRANCH}'
            }

            // Setup the kitchen required bundle
            stage('Setup') {
                if ( macos_build ) {
                    sh 'bundle install --with vagrant macos --without ec2 windows opennebula docker'
                } else {
                    sh 'bundle install --with ec2 windows --without docker macos opennebula vagrant'
                }
            }

            stage('Create VM') {
                if ( macos_build ) {
                    sh '''
                    bundle exec kitchen create $TEST_SUITE-$TEST_PLATFORM; echo "ExitCode: $?";
                    '''
                    sh """
                    if [ -s ".kitchen/logs/${python_version}-${distro_name}-${distro_version}.log" ]; then
                        mv ".kitchen/logs/${python_version}-${distro_name}-${distro_version}.log" ".kitchen/logs/${python_version}-${distro_name}-${distro_version}-create.log"
                    fi
                    if [ -s ".kitchen/logs/kitchen.log" ]; then
                        mv ".kitchen/logs/kitchen.log" ".kitchen/logs/kitchen-create.log"
                    fi
                    """
                } else {
                    retry(3) {
                        if ( use_spot_instances ) {
                            sh '''
                            cp -f ~/workspace/spot.yml .kitchen.local.yml
                            t=$(shuf -i 30-120 -n 1); echo "Sleeping $t seconds"; sleep $t
                            bundle exec kitchen create $TEST_SUITE-$TEST_PLATFORM || (bundle exec kitchen destroy $TEST_SUITE-$TEST_PLATFORM; rm .kitchen.local.yml; bundle exec kitchen create $TEST_SUITE-$TEST_PLATFORM); echo "ExitCode: $?";
                            rm -f .kitchen.local.yml
                            '''
                        } else {
                            sh '''
                            t=$(shuf -i 30-120 -n 1); echo "Sleeping $t seconds"; sleep $t
                            bundle exec kitchen create $TEST_SUITE-$TEST_PLATFORM; echo "ExitCode: $?";
                            '''
                        }
                        sh """
                        if [ -s ".kitchen/logs/${python_version}-${distro_name}-${distro_version}.log" ]; then
                            mv ".kitchen/logs/${python_version}-${distro_name}-${distro_version}.log" ".kitchen/logs/${python_version}-${distro_name}-${distro_version}-create.log"
                        fi
                        if [ -s ".kitchen/logs/kitchen.log" ]; then
                            mv ".kitchen/logs/kitchen.log" ".kitchen/logs/kitchen-create.log"
                        fi
                        """
                    }
                    sh '''
                    bundle exec kitchen diagnose $TEST_SUITE-$TEST_PLATFORM | grep 'image_id:'
                    bundle exec kitchen diagnose $TEST_SUITE-$TEST_PLATFORM | grep 'instance_type:' -A5
                    '''
                }
            }

            try {
                timeout(time: testrun_timeout, unit: 'HOURS') {
                    stage('Converge VM') {
                        if ( macos_build ) {
                            sh '''
                            ssh-agent /bin/bash -c 'ssh-add ~/.vagrant.d/insecure_private_key; bundle exec kitchen converge $TEST_SUITE-$TEST_PLATFORM; echo "ExitCode: $?"'
                            '''
                        } else {
                            sh '''
                            ssh-agent /bin/bash -c 'ssh-add ~/.ssh/kitchen.pem; bundle exec kitchen converge $TEST_SUITE-$TEST_PLATFORM; echo "ExitCode: $?"'
                            '''
                        }
                        sh """
                        if [ -s ".kitchen/logs/${python_version}-${distro_name}-${distro_version}.log" ]; then
                            mv ".kitchen/logs/${python_version}-${distro_name}-${distro_version}.log" ".kitchen/logs/${python_version}-${distro_name}-${distro_version}-converge.log"
                        fi
                        if [ -s ".kitchen/logs/kitchen.log" ]; then
                            mv ".kitchen/logs/kitchen.log" ".kitchen/logs/kitchen-converge.log"
                        fi
                        """
                    }
                    stage('Run Tests') {
                        withEnv(["DONT_DOWNLOAD_ARTEFACTS=1"]) {
                            sh 'bundle exec kitchen verify $TEST_SUITE-$TEST_PLATFORM; echo "ExitCode: $?";'
                        }
                    }
                }
            } finally {
                try {
                    sh """
                    if [ -s ".kitchen/logs/${python_version}-${distro_name}-${distro_version}.log" ]; then
                        mv ".kitchen/logs/${python_version}-${distro_name}-${distro_version}.log" ".kitchen/logs/${python_version}-${distro_name}-${distro_version}-verify.log"
                    fi
                    if [ -s ".kitchen/logs/kitchen.log" ]; then
                        mv ".kitchen/logs/kitchen.log" ".kitchen/logs/kitchen-verify.log"
                    fi
                    """
                    stage('Download Artefacts') {
                        withEnv(["ONLY_DOWNLOAD_ARTEFACTS=1"]){
                            sh '''
                            bundle exec kitchen verify $TEST_SUITE-$TEST_PLATFORM || exit 0
                            '''
                        }
                        sh """
                        if [ -s ".kitchen/logs/${python_version}-${distro_name}-${distro_version}.log" ]; then
                            mv ".kitchen/logs/${python_version}-${distro_name}-${distro_version}.log" ".kitchen/logs/${python_version}-${distro_name}-${distro_version}-download.log"
                        fi
                        if [ -s ".kitchen/logs/kitchen.log" ]; then
                            mv ".kitchen/logs/kitchen.log" ".kitchen/logs/kitchen-download.log"
                        fi
                        """
                    }
                    archiveArtifacts(
                        artifacts: "artifacts/*,artifacts/**/*,.kitchen/logs/*-create.log,.kitchen/logs/*-converge.log,.kitchen/logs/*-verify.log,.kitchen/logs/*-download.log,artifacts/xml-unittests-output/*.xml",
                        allowEmptyArchive: true
                    )
                    junit 'artifacts/xml-unittests-output/*.xml'
                } finally {
                    stage('Cleanup') {
                        sh '''
                        bundle exec kitchen destroy $TEST_SUITE-$TEST_PLATFORM; echo "ExitCode: $?";
                        '''
                    }
                    stage('Upload Coverage') {
                        if ( run_full ) {
                            uploadCodeCoverage(
                                'artifacts/coverage/salt.xml',
                                "${distro_name}-${distro_version}-${python_version}-${nox_env_name.toLowerCase()}-salt",
                                (
                                    [
                                        "${distro_name}${distro_version}",
                                        python_version,
                                    ] + nox_env_name.split(',') + [
                                        'salt'
                                    ]
                                ).flatten()
                            )
                            uploadCodeCoverage(
                                'artifacts/coverage/tests.xml',
                                "${distro_name}-${distro_version}-${python_version}-${nox_env_name.toLowerCase()}-tests",
                                (
                                    [
                                        "${distro_name}${distro_version}",
                                        python_version,
                                    ] + nox_env_name.split(',') + [
                                        'tests'
                                    ]
                                ).flatten()
                            )
                        }
                    }
                }
            }
        }
    }
}
// vim: ft=groovy ts=4 sts=4 et
