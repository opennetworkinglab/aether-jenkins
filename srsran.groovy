// SPDX-FileCopyrightText: 2021 Open Networking Foundation <info@opennetworking.org>
// SPDX-License-Identifier: LicenseRef-ONF-Member-Only-1.0

pipeline {
  options {
      timeout(time: 1, unit: 'HOURS')
  }

  agent {
        label "${AgentLabel}"
  }

  stages{

    stage('Verify AWS Accessible') {
        steps {
          withCredentials([aws(accessKeyVariable: 'AWS_ACCESS_KEY_ID',
              credentialsId: 'AKIA6OOX34YQ5DJLY5GJ',
              secretKeyVariable: 'AWS_SECRET_ACCESS_KEY')]) {
            sh """
              set -e
              aws --region us-west-2 ec2 start-instances --instance-ids    i-000f1f7e33fe5a86e
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
              credentialsId: 'AKIA6OOX34YQ5DJLY5GJ', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY')]) {
            sh """
              set -e
              NEWIP=\$(aws --region us-west-2 ec2 describe-instances \
                           --instance-ids i-000f1f7e33fe5a86e \
                           --query 'Reservations[0].Instances[0].PrivateIpAddress')
              echo \$NEWIP
              WORKERIP=\$(echo \$NEWIP | tr -d '"')
              echo \$WORKERIP
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
node1 ansible_host=\$MYIP ansible_user=ubuntu ansible_ssh_private_key_file=/home/ubuntu/aether-qa.pem ansible_sudo_pass=ubuntu
node2 ansible_host=\$WORKERIP ansible_user=ubuntu ansible_ssh_private_key_file=/home/ubuntu/aether-qa.pem ansible_sudo_pass=ubuntu

[master_nodes]
node1

[worker_nodes]
#node2

[srsran_nodes]
node2
EOF
              cat hosts.ini
              NODE2_IP=\$(grep ansible_host hosts.ini | grep node2 | awk -F" |=" '{print \$3}')
              echo "NODE2_IP is " \$NODE2_IP
              sudo cp  vars/main-srsran.yml  vars/main.yml
              grep -rl "ens18" . | xargs sed -i "s/ens18/\$MYIFC/g"
              sudo sed -i "s/10.76.28.113/\$MYIP/" vars/main.yml
              sudo sed -i "s/172.20.0.2/\$NODE2_IP/" vars/main.yml
              sudo sed -i "s/ran_subnet:.*/ran_subnet: \"192.168.163.0\\\\/24\"/" vars/main.yml
              make aether-pingall
            """
          }
        }
    }

    stage('Install Aether') {
        steps {
          sh """
            cd $WORKSPACE/aether-onramp
            make aether-k8s-install
            make aether-5gc-install
            sleep 10
            make srsran-gnb-install
            kubectl get pods -n aether-5gc
          """
        }
    }

    stage("Run srs-uesim"){
        steps {
            catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE') {
                sh """
                    set -e
                    cd $WORKSPACE/aether-onramp
                    make srsran-uesim-start
                """
            }
        }
    }

    stage("Validate Results"){
        steps {
          catchError(message:'SRSRAN Validation is failed', buildResult:'FAILURE',
            stageResult:'FAILURE') {
              sh """
                set -e
                cd $WORKSPACE/aether-onramp
                NODE2_IP=\$(grep ansible_host hosts.ini | grep node2 | awk -F" |=" '{print \$3}')
                cd /home/ubuntu
                ssh -i "aether-qa.pem" -o StrictHostKeyChecking=no ubuntu@\$NODE2_IP \
                    "docker exec rfsim5g-srsran-nr-ue ip netns exec ue1 ping -c 2 192.168.250.1 > /home/ubuntu/srs-uesim.log"
                scp -i "aether-qa.pem" -o StrictHostKeyChecking=no \
                     ubuntu@\$NODE2_IP:/home/ubuntu/srs-uesim.log /home/ubuntu
                grep "0% packet loss" srs-uesim.log
              """
          }
        }
    }

    stage("Retrieve Logs") {
        steps {
            sh """
              set -e
              mkdir $WORKSPACE/logs
              cd $WORKSPACE/aether-onramp
              NODE2_IP=\$(grep ansible_host hosts.ini | grep node2 | awk -F" |=" '{print \$3}')
              cd /home/ubuntu
              scp -i "aether-qa.pem" -o StrictHostKeyChecking=no \
                     ubuntu@\$NODE2_IP:/home/ubuntu/srs-uesim.log $WORKSPACE/logs
              cd $WORKSPACE/logs
              AMF_POD_NAME=\$(kubectl get pods -n aether-5gc | grep amf | awk 'NR==1{print \$1}')
              echo \$AMF_POD_NAME
              kubectl logs \$AMF_POD_NAME -n aether-5gc > srsran_amf.log
              WEBUI_POD_NAME=\$(kubectl get pods -n aether-5gc | grep webui | awk 'NR==1{print \$1}')
              echo \$WEBUI_POD_NAME
              kubectl logs \$WEBUI_POD_NAME -n aether-5gc > srsran_webui.log
              UDR_POD_NAME=\$(kubectl get pods -n aether-5gc | grep udr | awk 'NR==1{print \$1}')
              echo \$UDR_POD_NAME
              kubectl logs \$UDR_POD_NAME -n aether-5gc > srsran_udr.log
              UDM_POD_NAME=\$(kubectl get pods -n aether-5gc | grep udm | awk 'NR==1{print \$1}')
              echo \$UDM_POD_NAME
              kubectl logs \$UDM_POD_NAME -n aether-5gc > srsran_udm.log
              AUSF_POD_NAME=\$(kubectl get pods -n aether-5gc | grep ausf | awk 'NR==1{print \$1}')
              echo \$AUSF_POD_NAME
              kubectl logs \$AUSF_POD_NAME -n aether-5gc > srsran_ausf.log
              SMF_POD_NAME=\$(kubectl get pods -n aether-5gc | grep smf | awk 'NR==1{print \$1}')
              echo \$SMF_POD_NAME
              kubectl logs \$SMF_POD_NAME -n aether-5gc > srsran_smf.log
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
          set -e
          cd $WORKSPACE/aether-onramp
          make srsran-uesim-stop
          make srsran-gnb-uninstall
          make 5gc-uninstall
          make k8s-uninstall
          aws --region us-west-2 ec2 stop-instances --instance-ids    i-000f1f7e33fe5a86e
        """
      }
    }

    // triggered when red sign
    failure {
        slackSend color: "danger", message: "FAILED ${env.JOB_NAME} ${env.BUILD_NUMBER} ${env.BUILD_URL}"

    }
  }
}
