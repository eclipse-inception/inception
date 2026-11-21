--
-- Licensed to the Technische Universität Darmstadt under one
--  or more contributor license agreements.  See the NOTICE file
-- distributed with this work for additional information
-- regarding copyright ownership.  The Technische Universität Darmstadt
-- licenses this file to you under the Apache License, Version 2.0 (the
-- "License"); you may not use this file except in compliance
-- with the License.
--
-- http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing, software
-- distributed under the License is distributed on an "AS IS" BASIS,
-- WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
-- See the License for the specific language governing permissions and
-- limitations under the License.


-- Lays out the window that opens when the DMG is mounted. jpackage runs it while
-- building the DMG, after replacing the DEPLOY_* placeholders; it is based on
-- jpackage's own DMGsetup.scpt.
--
-- The window content is 640 x 530, the size of background.tiff. The icon
-- positions match the free spaces in background.svg: the application and the
-- Applications folder left and right of the arrow, the legal folder bottom left.
-- The bounds add 28 pixels for the title bar.

tell application "Finder"
  set theDisk to a reference to (disks whose URL = "DEPLOY_VOLUME_URL")
  open theDisk

  set theWindow to a reference to (container window of disks whose URL = "DEPLOY_VOLUME_URL")

  set current view of theWindow to icon view
  set toolbar visible of theWindow to false
  set statusbar visible of theWindow to false

  set the bounds of theWindow to {400, 100, 1040, 658}

  set theViewOptions to a reference to the icon view options of theWindow
  set arrangement of theViewOptions to not arranged
  set icon size of theViewOptions to 96
  set background picture of theViewOptions to POSIX file "DEPLOY_BG_FILE"

  -- Create alias for install location
  do shell script "(cd 'DEPLOY_VOLUME_PATH' && ln -s 'DEPLOY_INSTALL_LOCATION' 'DEPLOY_INSTALL_LOCATION_DISPLAY_NAME')"

  set allTheFiles to the name of every item of theWindow
  set xpos to 100
  repeat with theFile in allTheFiles
    set theFilePath to POSIX path of theFile
    set appFilePath to POSIX path of "/DEPLOY_TARGET"
    if theFilePath ends with "DEPLOY_INSTALL_LOCATION_DISPLAY_NAME" then
      set position of item theFile of theWindow to {440, 268}
    else if theFilePath ends with "DEPLOY_INSTALL_LOCATION" then
      set position of item theFile of theWindow to {440, 268}
    else if theFilePath ends with appFilePath then
      set position of item theFile of theWindow to {200, 268}
    else
      -- The legal folder; anything else added later continues the row
      set position of item theFile of theWindow to {xpos, 405}
      set xpos to xpos + 120
    end if
  end repeat

  update theDisk without registering applications
  delay 5
  close (get window of theDisk)
end tell
