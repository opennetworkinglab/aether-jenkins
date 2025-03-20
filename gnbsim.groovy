// SPDX-FileCopyrightText: 2021 Open Networking Foundation <info@opennetworking.org>
// SPDX-License-Identifier: LicenseRef-ONF-Member-Only-1.0
// gnbsim.groovy

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

    stage('Verify AWS Accessible') {
        steps {
          withCredentials([aws(accessKeyVariable: 'AWS_ACCESS_KEY_ID',
              credentialsId: 'AKIA6OOX34YQ5DJLY5GJ',
              secretKeyVariable: 'AWS_SECRET_ACCESS_KEY')]) {
            sh """
              aws --region us-west-2 ec2 start-instances --instance-ids i-000f1f7e33fe5a86e
              aws --region us-west-2 ec2 modify-instance-attribute --no-source-dest-check \
                  --instance-id i-000f1f7e33fe5a86e
              aws --region us-west-2 ec2 describe-instances --instance-ids i-000f1f7e33fe5a86e
              sleep 300
              aws --region us-west-2 ec2 describe-instances --instance-ids i-000f1f7e33fe5a86e \
                  --query 'Reservations[0].Instances[0].PrivateIpAddress'
            """
          }
        }
    }

    stage('Configure OnRamp') {
        steps {
          withCredentials([aws(accessKeyVariable: 'AWS_ACCESS_KEY_ID',
              credentialsId: 'AKIA6OOX34YQ5DJLY5GJ', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'),
              sshUserPrivateKey(credentialsId: 'aether-qa', keyFileVariable: 'aether_qa')]) {
            sh '''
              if [[ -f $VENV_PATH/bin/activate ]]; then
                source $VENV_PATH/bin/activate
              fi
              NEWIP=\$(aws --region us-west-2 ec2 describe-instances \
                           --instance-ids i-000f1f7e33fe5a86e \
                           --query 'Reservations[0].Instances[0].PrivateIpAddress')
              echo \$NEWIP
              WORKERIP=\$(echo \$NEWIP | tr -d '"')
              echo \$WORKERIP
              cp -p "$aether_qa" "$PEM_PATH"
              cd $WORKSPACE
              git clone --recursive https://github.com/opennetworkinglab/aether-onramp.git
              cd aether-onramp
              # Determine Local IP
              MYIP=\$(hostname -I | awk '{print \$1}')
              echo "MY IP is: " \$MYIP
              which curl
              which wget
              MYID=\$(wget -q -O - http://169.254.169.254/latest/meta-data/instance-id)
              echo "MYID is " \$MYID
              aws --region us-west-2 ec2 modify-instance-attribute --no-source-dest-check \
                  --instance-id \$MYID
              echo "WORKER SourceDestCheck"
              aws --region us-west-2 ec2 describe-instances --instance-ids i-000f1f7e33fe5a86e \
                  --query 'Reservations[0].Instances[0].NetworkInterfaces[0].SourceDestCheck'
              echo "MAIN SourceDestCheck"
              aws --region us-west-2 ec2 describe-instances --instance-ids \$MYID \
                  --query 'Reservations[0].Instances[0].NetworkInterfaces[0].SourceDestCheck'
              # Determine active network interface (8.8.8.8 is Google)
              MYIFC=\$(ip route get 8.8.8.8| awk '{print \$5}'|awk /./)
              echo "MY IFC is: " \$MYIFC
              # Create appropriate hosts.ini file
              cat > hosts.ini << EOF
[all]
node1 ansible_host=\$MYIP ansible_user=ubuntu ansible_ssh_private_key_file=$PEM_PATH ansible_sudo_pass=ubuntu
node2 ansible_host=\$WORKERIP ansible_user=ubuntu ansible_ssh_private_key_file=$PEM_PATH ansible_sudo_pass=ubuntu

[master_nodes]
node1

[worker_nodes]
#node2

[gnbsim_nodes]
node2
EOF
              cat hosts.ini
              NODE2_IP=\$(grep ansible_host hosts.ini | grep node2 | awk -F" |=" '{print \$3}')
              echo "NODE2_IP is " \$NODE2_IP
              sleep 120
              sudo cp  vars/main-gnbsim.yml  vars/main.yml
              grep -rl "ens18" . | xargs sed -i "s/ens18/\$MYIFC/g"
              sudo sed -i "s/10.76.28.113/\$MYIP/" vars/main.yml
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
            make aether-gnbsim-install
            kubectl get pods -n aether-5gc
          """
        }
    }

    stage("Run gNBsim"){
        steps {
          sh """
            if [[ -f $VENV_PATH/bin/activate ]]; then
              source $VENV_PATH/bin/activate
            fi
            cd $WORKSPACE/aether-onramp
            NODE2_IP=\$(grep ansible_host hosts.ini | grep node2 | awk -F" |=" '{print \$3}')
            sleep 60
            make aether-gnbsim-run
            cd /home/ubuntu
            ssh -i "$PEM_PATH" -o StrictHostKeyChecking=no ubuntu@\$NODE2_IP \
               "docker exec  gnbsim-1 cat summary.log"
            ssh -i "$PEM_PATH" -o StrictHostKeyChecking=no ubuntu@\$NODE2_IP \
               "docker exec  gnbsim-2 cat summary.log"
          """
        }
    }

    stage("Validate Results"){
        steps {
          catchError(message:'Gnbsim Validation is failed', buildResult:'FAILURE',
          stageResult:'FAILURE') {
            sh """
              if [[ -f $VENV_PATH/bin/activate ]]; then
                source $VENV_PATH/bin/activate
              fi
              cd $WORKSPACE/aether-onramp
              NODE2_IP=\$(grep ansible_host hosts.ini | grep node2 | awk -F" |=" '{print \$3}')
              cd /home/ubuntu
              # weaker validation test
              ssh -i "$PEM_PATH" -o StrictHostKeyChecking=no ubuntu@\$NODE2_IP \
                  "docker exec gnbsim-1 cat summary.log" | grep "Ue's Passed" | grep -v "Passed: 0"
              ssh -i "$PEM_PATH" -o StrictHostKeyChecking=no ubuntu@\$NODE2_IP \
                  "docker exec gnbsim-2 cat summary.log" | grep "Ue's Passed" | grep -v "Passed: 0"
            """
          }
        }
    }

    stage("Retrieve Logs") {
        steps {
          sh """
            if [[ -f $VENV_PATH/bin/activate ]]; then
              source $VENV_PATH/bin/activate
            fi
            mkdir $WORKSPACE/logs
            cd $WORKSPACE/aether-onramp
            NODE2_IP=\$(grep ansible_host hosts.ini | grep node2 | awk -F" |=" '{print \$3}')
            cd /home/ubuntu
            LOGFILE=\$(ssh -i "$PEM_PATH" -o StrictHostKeyChecking=no ubuntu@\$NODE2_IP \
                   "docker exec  gnbsim-1 ls " | grep "gnbsim1-.*.log")  || true
            echo \$LOGFILE
            ssh -i "$PEM_PATH" -o StrictHostKeyChecking=no ubuntu@\$NODE2_IP \
                   "docker cp gnbsim-1:/gnbsim/\$LOGFILE  /home/ubuntu/\$LOGFILE"
            scp -i "$PEM_PATH" -o StrictHostKeyChecking=no \
                   ubuntu@\$NODE2_IP:\$LOGFILE $WORKSPACE/logs
            LOGFILE=\$(ssh -i "$PEM_PATH" -o StrictHostKeyChecking=no ubuntu@\$NODE2_IP \
                   "docker exec  gnbsim-2 ls " | grep "gnbsim2-.*.log")  || true
            echo \$LOGFILE
            ssh -i "$PEM_PATH" -o StrictHostKeyChecking=no ubuntu@\$NODE2_IP \
                   "docker cp gnbsim-2:/gnbsim/\$LOGFILE  /home/ubuntu/\$LOGFILE"
            scp -i "$PEM_PATH" -o StrictHostKeyChecking=no \
                   ubuntu@\$NODE2_IP:\$LOGFILE $WORKSPACE/logs
            cd $WORKSPACE/logs
            AMF_POD_NAME=\$(kubectl get pods -n aether-5gc | grep amf | awk 'NR==1{print \$1}')
            echo \$AMF_POD_NAME
            kubectl logs \$AMF_POD_NAME -n aether-5gc > gnbsim_amf.log
            WEBUI_POD_NAME=\$(kubectl get pods -n aether-5gc | grep webui | awk 'NR==1{print \$1}')
            echo \$WEBUI_POD_NAME
            kubectl logs \$WEBUI_POD_NAME -n aether-5gc > gnbsim_webui.log
            UDR_POD_NAME=\$(kubectl get pods -n aether-5gc | grep udr | awk 'NR==1{print \$1}')
            echo \$UDR_POD_NAME
            kubectl logs \$UDR_POD_NAME -n aether-5gc > gnbsim_udr.log
            UDM_POD_NAME=\$(kubectl get pods -n aether-5gc | grep udm | awk 'NR==1{print \$1}')
            echo \$UDM_POD_NAME
            kubectl logs \$UDM_POD_NAME -n aether-5gc > gnbsim_udm.log
            AUSF_POD_NAME=\$(kubectl get pods -n aether-5gc | grep ausf | awk 'NR==1{print \$1}')
            echo \$AUSF_POD_NAME
            kubectl logs \$AUSF_POD_NAME -n aether-5gc > gnbsim_ausf.log
            SMF_POD_NAME=\$(kubectl get pods -n aether-5gc | grep smf | awk 'NR==1{print \$1}')
            echo \$SMF_POD_NAME
            kubectl logs \$SMF_POD_NAME -n aether-5gc > gnbsim_smf.log
          """
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
      withCredentials([aws(accessKeyVariable: 'AWS_ACCESS_KEY_ID',
        credentialsId: 'AKIA6OOX34YQ5DJLY5GJ', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY')]) {
        sh """
          if [[ -f $VENV_PATH/bin/activate ]]; then
            source $VENV_PATH/bin/activate
          fi
          cd $WORKSPACE/aether-onramp
          make gnbsim-uninstall
          make 5gc-uninstall
          make k8s-uninstall
          aws --region us-west-2 ec2 stop-instances --instance-ids i-000f1f7e33fe5a86e
        """
      }
    }

    // triggered when red sign
    failure {
        slackSend color: "danger", message: "FAILED ${env.JOB_NAME} ${env.BUILD_NUMBER} ${env.BUILD_URL}"
    }
  }
}
