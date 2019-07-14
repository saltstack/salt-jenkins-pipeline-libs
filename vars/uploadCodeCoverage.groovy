
def call(String credentials_id = 'codecov-upload-token-salt') {
    stage('Upload Coverage') {
        retry(3) {
            timeout(time: 5, unit: 'MINUTES') {
                script {
                    withCredentials([[$class: 'StringBinding', credentialsId: 'codecov-upload-token-salt', variable: 'CODECOV_TOKEN']]) {
                        sh '''
                        if [ -f artifacts/coverage/coverage.xml ]; then
                            curl -L https://codecov.io/bash | /bin/sh -s -- -R $(pwd) -s artifacts/coverage/ -F "${CODECOV_FLAGS}"
                        fi
                        '''
                    }
                }
            }
        }
    }
}
// vim: ft=groovy ts=4 sts=4 et
