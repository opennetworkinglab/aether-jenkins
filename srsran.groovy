// SPDX-FileCopyrightText: 2021 Open Networking Foundation <info@opennetworking.org>
// SPDX-FileCopyrightText: 2025 Intel Corporation
// SPDX-License-Identifier: LicenseRef-ONF-Member-Only-1.0
// srsran.groovy

pipeline {
  options {
    timeout(time: 1, unit: 'HOURS')
  }

  agent {
    label "${AgentLabel}"
  }

  environment {
    PEM_PATH = "/home/ubuntu/aether-qa.pem"
    VENV_PATH = "/home/ubuntu/ubuntu_venv"
  }

  stages{

    stage('Configure OnRamp') {
        steps {
            withCredentials([sshUserPrivateKey(credentialsId: 'aether-qa',
            keyFileVariable: 'aether_qa', usernameVariable: 'aether_qa_user')]) {
              sh '''
                if [[ -f $VENV_PATH/bin/activate ]]; then
                  source $VENV_PATH/bin/activate
                fi
                cp -p "$aether_qa" "$PEM_PATH"
                git clone --recursive https://github.com/opennetworkinglab/aether-onramp.git
                cd aether-onramp
                MYIP=\$(hostname -I | awk '{print \$1}')
                MYIFC=\$(ip route get 8.8.8.8| awk '{print \$5}'|awk /./)
                MYIPNET=\$(ip addr show \$MYIFC | grep 'inet ' | awk '{print $2}')
                cat > hosts.ini << EOF
[all]
node1 ansible_host=\$MYIP ansible_user=ubuntu ansible_ssh_private_key_file=$PEM_PATH ansible_sudo_pass=ubuntu

[master_nodes]
node1

[worker_nodes]
#node2

[srsran_nodes]
node1
EOF
                cat hosts.ini
                VARS_MAIN="vars/main.yml"
                cp vars/main-srsran.yml "$VARS_MAIN"
                sed -i "s|ens18|\$MYIFC|g" "$VARS_MAIN"
                sed -i "s|10.76.28.113|\$MYIP|" "$VARS_MAIN"
                sed -i 's|172.20.0.0/16|""|' "$VARS_MAIN"
                sed -i "s|10.76.28.115|\$MYIP|" "$VARS_MAIN"
                sed -i "s|10.76.28.0/24|\$MYIPNET|" "$VARS_MAIN"
                git diff $VARS_MAIN
                make aether-pingall
            '''
          }
        }
    }

    stage('Install Aether') {
        steps {
          sh """
            if [[ -f $VENV_PATH/bin/activate ]]; then
              source $VENV_PATH/bin/activate
            fi
            cd $WORKSPACE/aether-onramp
            make aether-k8s-install
            make aether-5gc-install
            sleep 10
            make aether-srsran-gnb-install
            kubectl get pods -n aether-5gc
            docker ps
            sleep 10
          """
        }
    }

    stage('Run srs-uesim'){
        steps {
            catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE') {
                sh """
                    if [[ -f $VENV_PATH/bin/activate ]]; then
                      source $VENV_PATH/bin/activate
                    fi
                    cd $WORKSPACE/aether-onramp
                    make aether-srsran-uesim-start
                """
            }
        }
    }

    stage('Validate Results'){
        steps {
          catchError(message:'SRSRAN Validation is failed', buildResult:'FAILURE',
            stageResult:'FAILURE') {
              sh """
                if [[ -f $VENV_PATH/bin/activate ]]; then
                  source $VENV_PATH/bin/activate
                fi
                cd $WORKSPACE/aether-onramp
                docker exec rfsim5g-srsran-nr-ue ip netns exec ue1 ping -c 5 192.168.250.1 > srs-uesim.log
                grep "0% packet loss" srs-uesim.log
              """
          }
        }
    }

    stage('Retrieve Logs'){
        steps {
            sh '''
              if [[ -f $VENV_PATH/bin/activate ]]; then
                source $VENV_PATH/bin/activate
              fi
              mkdir $WORKSPACE/logs
              cd $WORKSPACE/logs
              docker logs srsran-gnb > srsran_gnb.log
              docker logs rfsim5g-srsran-nr-ue > srsran_ue.log
              AMF_POD_NAME=\$(kubectl get pods -n aether-5gc | grep amf | awk 'NR==1{print \$1}')
              echo "${AMF_POD_NAME}"
              kubectl logs $AMF_POD_NAME -n aether-5gc > srsran_amf.log
              WEBUI_POD_NAME=\$(kubectl get pods -n aether-5gc | grep webui | awk 'NR==1{print \$1}')
              echo "${WEBUI_POD_NAME}"
              kubectl logs $WEBUI_POD_NAME -n aether-5gc > srsran_webui.log
              UDR_POD_NAME=\$(kubectl get pods -n aether-5gc | grep udr | awk 'NR==1{print \$1}')
              echo "${UDR_POD_NAME}"
              kubectl logs $UDR_POD_NAME -n aether-5gc > srsran_udr.log
              UDM_POD_NAME=\$(kubectl get pods -n aether-5gc | grep udm | awk 'NR==1{print \$1}')
              echo "${UDM_POD_NAME}"
              kubectl logs $UDM_POD_NAME -n aether-5gc > srsran_udm.log
              AUSF_POD_NAME=\$(kubectl get pods -n aether-5gc | grep ausf | awk 'NR==1{print \$1}')
              echo "${AUSF_POD_NAME}"
              kubectl logs $AUSF_POD_NAME -n aether-5gc > srsran_ausf.log
              SMF_POD_NAME=\$(kubectl get pods -n aether-5gc | grep smf | awk 'NR==1{print \$1}')
              echo "${SMF_POD_NAME}"
              kubectl logs $SMF_POD_NAME -n aether-5gc > srsran_smf.log
            '''
        }
    }

    stage("Archive Artifacts"){
        steps {
            archiveArtifacts allowEmptyArchive: true, artifacts: "**/logs/*.log", followSymlinks: false
        }
    }

  }

  post {
    always {
      sh """
        if [[ -f $VENV_PATH/bin/activate ]]; then
          source $VENV_PATH/bin/activate
        fi
        cd $WORKSPACE/aether-onramp
        make aether-srsran-uesim-stop
        make aether-srsran-gnb-uninstall
        make aether-5gc-uninstall
        make aether-k8s-uninstall
      """
    }

    // triggered when red sign
    failure {
        slackSend color: "danger", message: "FAILED ${env.JOB_NAME} ${env.BUILD_NUMBER} ${env.BUILD_URL}"
    }
  }
}
