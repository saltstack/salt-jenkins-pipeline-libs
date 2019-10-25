def call(Map options) {

    def String report_path = options.get('report_path')
    def String report_name = options.get('report_name')
    def String[] report_flags = options.get('report_flags')
    def Integer upload_retries = options.get('upload_retries', 3)
    def String credentials_id = options.get('credentials_id', 'codecov-upload-token-salt')
    def String credentials_variable_name = options.get('credentials_variable_name', 'CODECOV_TOKEN')

    withEnv([
        "REPORT_PATH=${report_path}",
        "REPORT_NAME=${report_name}",
        "REPORT_FLAGS=${report_flags.join(',')}"
    ]) {
        try {
            retry(upload_retries) {
                script {
                    withCredentials([[$class: 'StringBinding', credentialsId: credentials_id, variable: credentials_variable_name]]) {
                        sh '''
                        curl -L https://codecov.io/bash | /bin/sh -s -- -R $(pwd) -n "${REPORT_NAME}" -f "${REPORT_PATH}" -F "${REPORT_FLAGS}"
                        '''
                    }
                }
            }
        } catch (Exception e) {
            echo "Failed to upload to codecov after ${upload_retries}: ${e}"
        }
    }
}
// vim: ft=groovy ts=4 sts=4 et
