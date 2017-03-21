#!/bin/bash
# Downloads and runs an analyst worker.
# This will be run through string.format, and the following items will be available:
# {0}: the URL to grab the worker JAR from
# {1}: the AWS log group to use
# {2}: the worker configuration to use

# prep the system: install log agent, java
yum -y install awslogs java-1.8.0-openjdk

# first things first: set up logging
LOGFILE=/var/log/analyst-worker.log

echo Starting analyst worker at `date` > $LOGFILE

# make it so that the worker can write to the logfile
chown ec2-user:ec2-user $LOGFILE
chmod 664 $LOGFILE # Log agent needs to read log file

cat > /etc/awslogs.conf <<EOF
[general]
state_file = /var/awslogs/state/agent-state

[otp]
file = $LOGFILE
log_group_name = {1}
log_stream_name = \{instance_id\}
datetime_format = %Y-%m-%dT%H:%M:%S%z
time_zone = UTC
EOF

service awslogs start

cat > ~ec2-user/worker.conf <<EOF
{2}
EOF

# Download the worker
sudo -u ec2-user wget -O ~ec2-user/r5.jar {0} 2>&1 >> $LOGFILE

# Figure out how much memory to give the worker
# figure out how much memory to use
TOTAL_MEM=`grep MemTotal /proc/meminfo | sed 's/[^0-9]//g'`
# 2097152 kb is 2GB, leave that much for the OS
MEM=`echo $TOTAL_MEM - 2097152 | bc`

# Start the worker
# run in ec2-user's home directory
cd ~ec2-user
sudo -u ec2-user java8 -jar r5.jar worker worker.conf 2>&1 >> $LOGFILE

# If the worker exits or doesn't start, wait a few minutes so that the CloudWatch log agent grabs
# the logs
sleep 120
halt -p
