#!/bin/bash
# Licensed to the Technische Universität Darmstadt under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The Technische Universität Darmstadt
# licenses this file to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

set -x

if [ -n "$_JAVA_OPTIONS" ]; then
  export _JAVA_OPTIONS="$_JAVA_OPTIONS -XX:-UseBASE64Intrinsics"
fi

if [ "$(id -u)" = "0" ]; then
  # Update the user and group IDs for an existing user
  echo "Updating UID [$APP_UID] and GID [$APP_GID] for user [$APP_USER] and group [$APP_GROUP]..."
  usermod -u "$APP_UID" "$APP_USER"
  groupmod -g "$APP_GID" "$APP_GROUP"
  
  # Change the ownership of application files
  echo "Updating file ownership user inception - this may take a moment..."
  chown -R "$APP_USER":"$APP_GROUP" /opt/inception
  chown -R "$APP_USER":"$APP_GROUP" /export
  
  # Drop privileges and run the application as the non-privileged user
  # (e.g. when running via simple `docker run ...`
  echo "Launching application..."
  COMMAND="$(which $1)" 
  shift
  ARGUMENTS="$(printf "\"%s\" " "$@")"
  exec su -p -c "${COMMAND} ${ARGUMENTS}" "$APP_USER"
else
  # Privileges have already been dropped by the caller so we run as the
  # current user (e.g. in a typical Kubernetes deployment)
  exec $@
fi
