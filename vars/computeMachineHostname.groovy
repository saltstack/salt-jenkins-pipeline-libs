
def call(Map opts) {
    def env = opts.get('env')
    def String distro_name = opts.get('distro_name')
    def String distro_version = opts.get('distro_version')
    def String python_version = opts.get('python_version')
    def String nox_env_name = opts.get('nox_env_name')
    def String[] extra_parts = opts.get('extra_parts', [])

    def hostname_parts = [distro_name, distro_version]

    if ( env.CHANGE_ID ) {
        // This is a PR
        hostname_parts << "pr${env.CHANGE_ID}"
    } else {
        hostname_parts << "${env.CHANGE_TARGET}"
    }

    hostname_parts << python_version
    hostname_parts << nox_env_name.split('-')
    hostname_parts << extra_parts
    hostname_parts << "${env.BUILD_NUMBER}"

    def String machine_hostname = hostname_parts.flatten().join('-')

    def Map replacements = [
        master: 'mst',
        zeromq: 'zmq',
        runtests: 'rtst',
        pytest: 'ptst',
        ubuntu: 'ubtu',
        centos: 'cent',
        debian: 'deb',
        fedora: 'fed',
        windows: 'win',
        amazon: 'amzn',
        opensuse: 'osuse',
        m2crypto: 'm2c',
        pycryptodomex: 'pcrtodomex',
        tornado: 'trndo',
    ]

    replacements.each { original, replacement ->
        machine_hostname = machine_hostname.replace(original, replacement)
    }

    return machine_hostname
}
