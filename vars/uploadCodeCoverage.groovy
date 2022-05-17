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
                        if [ -f "${REPORT_PATH}" ]; then
                            n=0
                            until [ "$n" -ge 5 ]
                            do
                            if curl --max-time 30 -L https://uploader.codecov.io/latest/codecov-linux --output codecov-linux; then
                                break
                            fi
                            n=$((n+1))
                            sleep 15
                            done

                            n=0
                            until [ "$n" -ge 5 ]
                            do
                            if curl --max-time 30 -L https://uploader.codecov.io/latest/codecov-linux.SHA256SUM --output codecov-linux.SHA256SUM; then
                                break
                            fi
                            n=$((n+1))
                            sleep 15
                            done

                            n=0
                            until [ "$n" -ge 5 ]
                            do
                            if curl --max-time 30 -L https://uploader.codecov.io/latest/codecov-linux.SHA256SUM.sig --output codecov-linux.SHA256SUM.sig; then
                                break
                            fi
                            n=$((n+1))
                            sleep 15
                            done

                            n=0
                            until [ "$n" -ge 5 ]
                            do
                            if curl --max-time 30 -L https://keybase.io/codecovsecurity/pgp_keys.asc | gpg --import; then
                                break
                            fi
                            n=$((n+1))
                            sleep 15
                            done

                            gpg --verify codecov-linux.SHA256SUM.sig codecov-linux.SHA256SUM && \
                                shasum -a 256 -c codecov-linux.SHA256SUM && \
                                chmod +x codecov-linux || exit 1

                            ./codecov-linux -R $(pwd) -n "${REPORT_NAME}" -f "${REPORT_PATH}" -F "${REPORT_FLAGS}" || exit 1
                        fi
                        '''
                    }
                }
            }
        } catch (Exception e) {
            error_message = "Failed to upload '${report_path}' to codecov.io after ${upload_retries}: ${e}"
            echo error_message
            createSummary(
                id: "coverage-upload-${report_path.replace('/', '-')}",
                icon: 'warning.png',
                text: error_message
            )
        }
    }
}
// vim: ft=groovy ts=4 sts=4 et
