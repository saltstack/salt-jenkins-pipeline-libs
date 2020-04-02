def call(Map options) {

    def env = options.get('env')
    def String distro_name = options.get('distro_name')
    def String distro_version = options.get('distro_version')
    def String golden_images_branch = options.get('golden_images_branch')
    def Integer concurrent_builds = options.get('concurrent_builds', 1)
    def String jenkins_slave_label = options.get('jenkins_slave_label', 'kitchen-slave')
    def Boolean supports_py2 = options.get('supports_py2', true)
    def Boolean supports_py3 = options.get('supports_py3', true)
    def String ec2_region = options.get('ec2_region', 'us-west-2')

    // AWS
    def String ami_image_id
    def String ami_name_filter

    // Vagrant
    def String vagrant_box_name
    def String vagrant_box_version
    def String vagrant_box_provider
    def String vagrant_box_artifactory_repo
    def String vagrant_box_name_testing

    def Boolean image_created = false
    def Boolean tests_passed = false
    def Boolean is_pr_build = isPRBuild()

    def Boolean macos_build = false
    if ( distro_name == 'macosx' ) {
        macos_build = true
    }

    // Enforce build concurrency
    enforceBuildConcurrency(options)

    stage('Build Container') {
        ansiColor('xterm') {
            node(jenkins_slave_label) {
                try {
                    timeout(time: 1, unit: 'HOURS') {
                        timestamps {

                            // Checkout the repo
                            stage('Clone') {
                                checkout scm
                            }

                            sh """
                            if [ "\$(which packer)x" == "x" ] && [ ! -f bin/packer ]; then
                                mkdir -p bin
                                curl -O https://releases.hashicorp.com/packer/1.4.5/packer_1.4.5_linux_amd64.zip
                                curl -O https://releases.hashicorp.com/packer/1.4.5/packer_1.4.5_SHA256SUMS
                                sha256sum -c --ignore-missing packer_1.4.5_SHA256SUMS
                                unzip -d bin packer_1.4.5_linux_amd64.zip
                                export PATH="\${PWD}/bin:\${PATH}"
                            fi
                            pyenv install 3.6.8 || echo "We already have this python."
                            pyenv local 3.6.8
                            if [ ! -d venv ]; then
                                virtualenv venv
                            fi
                            . venv/bin/activate
                            pip install -r os-images/requirements/py3.6/base.txt
                            inv build-aws --staging --distro=${distro_name} --distro-version=${distro_version} --salt-branch=${golden_images_branch} --salt-pr=${env.CHANGE_ID}
                            """
                            image_created = true
                            ami_built_msg = "Built AMI ${ami_image_id}(${ami_name_filter})"
                            addInfoBadge(
                                id: 'build-ami-badge',
                                text: ami_built_msg
                            )
                            createSummary(
                                icon: "/images/48x48/attribute.png",
                                text: ami_built_msg
                            )
                        }
                    }
                } finally {
                    cleanWs notFailBuild: true
                }
            }
        }
    }

    if ( macos_build == false ) {
        options['ami_image_id'] = ami_image_id
    } else {
        options['vagrant_box'] = "${vagrant_box_name_testing}"
        options['vagrant_box_version'] = "${vagrant_box_version}"
        options['vagrant_box_provider'] = "${vagrant_box_provider}"
    }
    options['upload_test_coverage'] = false

    def Boolean run_tests = true
    try {
        if ( image_created == true ) {
            try {
                if ( is_pr_build == false ) {
                    message = "${distro_name}-${distro_version} AMI `${ami_image_id}` is built. Skip tests?"
                    try {
                        slack_message = "${message}\nPlease confirm or deny tests execution &lt;${env.BUILD_URL}|here&gt;"
                        slackSend(
                            channel: "#golden-images",
                            color: '#FF8243',
                            message: slack_message)
                    } catch (Exception e2) {
                        sh "echo Failed to send the Slack notification: ${e2}"
                    }

                    try {
                        timeout(time: 15, unit: 'MINUTES') {
                            run_tests = input(
                                id: 'run-tests',
                                message: "\n\n\n${message}\n",
                                parameters: [
                                    booleanParam(
                                        defaultValue: true,
                                        description: 'Push the button to skip tests.',
                                        name: 'Please confirm you agree with this'
                                    )
                                ]
                            )
                        }
                    } catch(t_err) { // timeout reached or input false
                        def user = t_err.getCauses()[0].getUser()
                        if( 'SYSTEM' == user.toString() ) { // SYSTEM means timeout.
                            run_tests = true
                        } else {
                            run_tests = false
                            echo "Test suite execution skipped by: [${user}]"
                        }
                    }
                }

                if ( run_tests == true ) {
                    if ( supports_py2 == true && supports_py3 == true ) {
                        parallel(
                            Py2: {
                                def py2_options = options.clone()
                                py2_options['python_version'] = 'py2'
                                runTests(py2_options)
                            },
                            Py3: {
                                def py3_options = options.clone()
                                py3_options['python_version'] = 'py3'
                                runTests(py3_options)
                            },
                            failFast: false
                        )
                        tests_passed = true
                    } else if ( supports_py2 == true ) {
                        options['python_version'] = 'py2'
                        runTests(options)
                        tests_passed = true
                    } else if ( supports_py3 == true ) {
                        options['python_version'] = 'py3'
                        runTests(options)
                        tests_passed = true
                    }
                } else {
                    tests_passed = true
                }
            } finally {
                if ( is_pr_build == false ) {
                    stage('Promote AMI') {
                        try {
                            try {
                                message = "${distro_name}-${distro_version} AMI `${ami_image_id}` is waiting for CI duties promotion."
                                if (tests_passed) {
                                    message = "${message}\nTests Passed"
                                } else {
                                    message = "${message}\n*Tests Failed. Take extra care before promoting*."
                                }
                                message = "${message}\nPlease confirm or deny promotion &lt;${env.BUILD_URL}|here&gt;"
                                slackSend(
                                    channel: "#golden-images",
                                    color: '#FF8243',
                                    message: message)
                            } catch (Exception e2) {
                                sh "echo Failed to send the Slack notification: ${e2}"
                            }
                            message = "${distro_name}-${distro_version} AMI `${ami_image_id}` is waiting for CI duties promotion."
                            if (tests_passed) {
                                message = "${message}\nTests Passed."
                            } else {
                                message = "${message}\nTests Failed. Take extra care before promoting."
                            }
                            timeout(time: 4, unit: 'HOURS') {
                                input id: 'promote-ami', message: "\n\n\n${message}\n\n\nPromote AMI ${ami_image_id}?\n", ok: 'Promote!'
                            }
                            node(jenkins_slave_label) {
                                try {
                                    checkout scm
                                    withAWS(credentials: 'os-imager-aws-creds', region: "${ec2_region}") {
                                        sh """
                                        if [ "\$(which packer)x" == "x" ] && [ ! -f bin/packer ]; then
                                            mkdir -p bin
                                            curl -O https://releases.hashicorp.com/packer/1.4.5/packer_1.4.5_linux_amd64.zip
                                            curl -O https://releases.hashicorp.com/packer/1.4.5/packer_1.4.5_SHA256SUMS
                                            sha256sum -c --ignore-missing packer_1.4.5_SHA256SUMS
                                            unzip -d bin packer_1.4.5_linux_amd64.zip
                                            export PATH="\${PWD}/bin:\${PATH}"
                                        fi
                                        pyenv install 3.6.8 || echo "We already have this python."
                                        pyenv local 3.6.8
                                        if [ ! -d venv ]; then
                                            virtualenv venv
                                        fi
                                        . venv/bin/activate
                                        pip install -r os-images/requirements/py3.6/base.txt
                                        inv promote-ami --image-id=${ami_image_id} --region=${ec2_region} --assume-yes
                                        """
                                    }
                                } finally {
                                    cleanWs notFailBuild: true
                                }
                            }
                            ami_built_msg = "AMI ${ami_image_id}(${ami_name_filter}) was promoted for CI duties!"
                            addBadge(
                                id: 'promoted-ami-badge',
                                icon: "/static/8361d0d6/images/16x16/accept.png",
                                text: ami_built_msg
                            )
                            createSummary(
                                icon: "/images/48x48/accept.png",
                                text: ami_built_msg
                            )
                            try {
                                slackSend(
                                    channel: "#golden-images",
                                    color: '#00FF00',
                                    message: "${distro_name}-${distro_version} AMI `${ami_image_id}` was promoted! (&lt;${env.BUILD_URL}|open&gt;)")
                            } catch (Exception e3) {
                                sh "echo Failed to send the Slack notification: ${e3}"
                            }
                        } catch (Exception e4) {
                            println "AMI ${ami_image_id} was NOT promoted for CI duties! Reason: ${e4}"
                            addWarningBadge(
                                id: 'promoted-ami-badge',
                                text: "AMI ${ami_image_id}(${ami_name_filter}) was NOT promoted for CI duties!"
                            )
                            createSummary(
                                icon: "/images/48x48/warning.png",
                                text: "AMI ${ami_image_id}(${ami_name_filter}) was &lt;b&gt;NOT&lt;/b&gt; promoted for CI duties!"
                            )
                            try {
                                slackSend(
                                    channel: "#golden-images",
                                    color: '#FF0000',
                                    message: "${distro_name}-${distro_version} AMI `${ami_image_id}` was *NOT* promoted! (&lt;${env.BUILD_URL}|open&gt;)")
                            } catch (Exception e5) {
                                sh "echo Failed to send the Slack notification: ${e5}"
                            }
                        }
                    }
                }
            }
        }
    } finally {
        stage('Cleanup Old AMIs') {
            if (ami_name_filter) {
                node(jenkins_slave_label) {
                    try {
                        timeout(time: 10, unit: 'MINUTES') {
                            checkout scm
                            withAWS(credentials: 'os-imager-aws-creds', region: "${ec2_region}") {
                                sh """
                                if [ "\$(which packer)x" == "x" ] && [ ! -f bin/packer ]; then
                                    mkdir -p bin
                                    curl -O https://releases.hashicorp.com/packer/1.4.5/packer_1.4.5_linux_amd64.zip
                                    curl -O https://releases.hashicorp.com/packer/1.4.5/packer_1.4.5_SHA256SUMS
                                    sha256sum -c --ignore-missing packer_1.4.5_SHA256SUMS
                                    unzip -d bin packer_1.4.5_linux_amd64.zip
                                    export PATH="\${PWD}/bin:\${PATH}"
                                fi
                                pyenv install 3.6.8 || echo "We already have this python."
                                pyenv local 3.6.8
                                if [ ! -d venv ]; then
                                    virtualenv venv
                                fi
                                . venv/bin/activate
                                pip install -r os-images/requirements/py3.6/base.txt
                                inv cleanup-aws --staging --name-filter='${ami_name_filter}' --region=${ec2_region} --assume-yes --num-to-keep=1
                                """
                            }
                        }
                    } finally {
                        cleanWs notFailBuild: true
                    }
                }
            }
        }
    }
}
// vim: ft=groovy ts=4 sts=4 et
