
def call(String version, Closure body=null) {

    def String path
    sh """
    if [ "\$(which packer)x" == "x" ] && [ ! -f bin/packer ]; then
        mkdir -p bin
        if [ "\$(uname -m)" == "x86_64" ]
        then
            curl -O https://releases.hashicorp.com/packer/${version}/packer_${version}_linux_amd64.zip
            curl -O https://releases.hashicorp.com/packer/${version}/packer_${version}_SHA256SUMS
            sha256sum -c --ignore-missing packer_${version}_SHA256SUMS
            unzip -d bin packer_${version}_linux_amd64.zip
        else
            curl -O https://releases.hashicorp.com/packer/${version}/packer_${version}_linux_arm64.zip
            curl -O https://releases.hashicorp.com/packer/${version}/packer_${version}_SHA256SUMS
            sha256sum -c --ignore-missing packer_${version}_SHA256SUMS
            unzip -d bin packer_${version}_linux_arm64.zip
        fi
    fi
    """
    command_output = sh returnStdout: true, script:
        '''
        if [ -f bin/packer ]; then
            export PATH="\${PWD}/bin:\${PATH}"
        fi
        echo \${PATH}
        '''
    path = command_output.trim()
    withEnv([
        "PATH=${path}"
    ]) {
        if (body) { body() }
    }
}
// vim: ft=groovy ts=4 sts=4 et
