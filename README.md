[<img src="https://gitlab.com/IzzyOnDroid/repo/-/raw/master/assets/IzzyOnDroidButtonGreyBorder_nofont.png" height="80" alt="Get it at IzzyOnDroid">](https://apt.izzysoft.de/packages/acab.naiveha.subrosa)

# sub rosa by naiveHA
Uncomplicatedly simple: if you manage your long, complex, and secure passwords in a password manager app (like KeePassDroid) on your Android device, 
you can now “type” them easily on any other device—phone, tablet, or PC—running Windows, Linux, or macOS.

*sub rosa* also lets you program the static password on your YubiKey, so you can “type” the password with the touch of a finger.
When you’re done “typing” your long, complex, and secure password, be sure to wipe your YubiKey clean. 
It isn’t much security if someone else can “type” your password by touching the YubiKey.

The various keyboards accept the following characters:

* *US*: space, \n, \t and abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789!"#$%&'`()*+-=,./:;<>?@\\][^_{}|~

* *UK*: space, \n, \t and abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789!@£$%&'`()*+-=,./:;<>?"#][^_{}~¬

* *DE*: space, \n, \t and abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789!"#$%&'()*+-=,./:;<>?^_`§´ÄÖÜßäöü

* *FR*: space, \n, \t and abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789!"$%&'()*+-=,./:;<_£§°²µàçèéù

* *IT*: space, \n, \t and abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789!"#$%&'()*+,-./:;<=>?@\^_`|£§°çèéàìòù

* *MODHEX*: bcdefghijklnrtuvBCDEFGHIJKLNRTUV

Since version 2.0.0, *sub rosa* has added support for OpenPGP. You can now provision an OpenPGP key onto your YubiKey or Nitrokey 3. 
In keeping with the app’s motto, “Uncomplicatedly simple,” you can encrypt, decrypt, sign, and authenticate with your security key, 
then wipe it clean and write another OpenPGP key to change your digital identity. 
SSH authentication is simpler now—you no longer need to reuse the same SSH key or buy multiple security keys

# How to import OpenPGP keys from OpenKeychain
*sub rosa* works closely with OpenKeychain. OpenKeychain manages OpenPGP keys, such as generating, storing them securely, and backing them up. 
Importing from OpenKeychain is now a breeze. Share a key backup with *sub rosa* and enter the backup code shown by OpenKeyring. 
For now, this backup code—made of 36 numbers and dashes—cannot be copied to the clipboard.
To make it “uncomplicatedly simple,” *sub rosa* includes Tesseract: an Optical Character Recognition library. 

Take a screenshot of the OpenKeyring app and share that screenshot with *sub rosa*.

Select the section that includes the backup code and hit the EXTRACT PASSWORD button. 

The backup code will be copied to the clipboard, and you can return to OpenKeyring and hit the SHARE BACKUP button.

Paste the backup code in *sub rosa* and get ready to write your private OpenPGP key to your security key.

<table>
  <tr>
    <td><img src="fastlane/metadata/android/en-US/images/phoneScreenshots/6.png" height="400"></td>
    <td><img src="fastlane/metadata/android/en-US/images/phoneScreenshots/7.png" height="400"></td>
    <td><img src="fastlane/metadata/android/en-US/images/phoneScreenshots/8.png" height="400"></td>
  </tr>
</table>

NB: *sub rosa* is not affiliated with OpenKeychain, we are just big fans of OpenKeychain

# How to use your security key for SSH authentication
When generating a new OpenPGP key with OpenKeyring, choose *Change key configuration* and add an Authentication subkey. 
Then import this OpenPGP key into *sub rosa* and write it to your security key.

On a clean Debian machine, ensure all the right packages are installed:

    sudo apt update
    sudo apt install openssh-client gnupg scdaemon pinentry-curses pcscd

Identify where pinentry is installed:

    which pinentry

The output should be something like:

    /usr/bin/pinentry

Take note of that, then configure gpg-agent to enable SSH support and use that pinentry:

    echo "enable-ssh-support" > ~/.gnupg/gpg-agent.conf
    echo "pinentry-program /usr/bin/pinentry" >> ~/.gnupg/gpg-agent.conf

Restart the agent to pick up the config:

    gpgconf --kill gpg-agent
    gpgconf --launch gpg-agent
    gpgconf --list-dirs agent-ssh-socket

This should display something like:

    /run/user/1000/gnupg/S.gpg-agent.ssh

Take note of it and add it to your ~/.ssh/config:

    echo "Host *" > ~/.ssh/config
    echo "  IdentityAgent /run/user/1000/gnupg/S.gpg-agent.ssh" >> ~/.ssh/config

Make sure to replace /run/user/1000/gnupg/S.gpg-agent.ssh with the correct value for your own system.

Now interrogate the security key:

    gpg-connect-agent "scd learn --force" /bye

This displays details of the OpenPGP key written on your security key.
The output looks something like this:

    ...
    S KEYPAIRINFO 5BBF795A5F93498C2FF6C6CD3ABF896302DC9FA0 OPENPGP.1 sc 1782525925 ed25519
    S KEYPAIRINFO D1B359A6A77A68B1776ABFA085E65AFA1582E6D6 OPENPGP.2 e 1782525925 cv25519
    S KEYPAIRINFO 36F40BE75662CEA929402AAA360A64F448E35ABB OPENPGP.3 a 1782525925 ed25519
    OK

To extract the SSH authentication key, run:

    echo "36F40BE75662CEA929402AAA360A64F448E35ABB" >> ~/.gnupg/sshcontrol
    gpgconf --kill gpg-agent
    export SSH_AUTH_SOCK=$(gpgconf --list-dirs agent-ssh-socket)
    ssh-add -L

Make sure to replace 36F40BE75662CEA929402AAA360A64F448E35ABB with the keygrip of your own Authentication subkey. 
The last command should display something like:

    ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIGfZlzyF4mwdtAnUNJVz1TxOkotdHNizIaA56IOepfA/ cardno:000F_D52FD320

Take note of that and add it to ~/.ssh/authorized_keys on your remote server.

When logging into your remote server via SSH, you'll be asked for a PIN. 
This is the User PIN (default 123456), not the Admin PIN (default 12345678).

NB: if the above instructions are not complete, open an issue and contribute to the project.

Coffee tips can be sent to: 1HwgShr1TniuBxNQwy2xAhpQaNuZhtw6sh

<table>
  <tr>
    <td><img src="fastlane/metadata/android/en-US/images/phoneScreenshots/0.png" height="400"></td>
    <td><img src="fastlane/metadata/android/en-US/images/phoneScreenshots/1.png" height="400"></td>
    <td><img src="fastlane/metadata/android/en-US/images/phoneScreenshots/2.png" height="400"></td>
  </tr>
  <tr>
    <td><img src="fastlane/metadata/android/en-US/images/phoneScreenshots/3.png" height="400"></td>
    <td><img src="fastlane/metadata/android/en-US/images/phoneScreenshots/4.png" height="400"></td>
    <td><img src="fastlane/metadata/android/en-US/images/phoneScreenshots/5.png" height="400"></td>
  </tr>
</table>

