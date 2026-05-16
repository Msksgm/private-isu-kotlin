# 競技用インスタンスのセットアップ方法

`catatsuy/private-isu` の AMI をベースに、isu-ruby から isu-kotlin へ切り替えるための Ansible playbook。

## 実行

```
$ ansible-playbook -i hosts image/ansible/playbooks.yml
```

## ssh config の例

```
Host isu-app
  IdentityFile ~/.ssh/xxx.pem
  HostName xxx.xxx.xxx.xxx
  User ubuntu
```

`hosts` の `REPLACE_ME_PUBLIC_IP` と `REPLACE_ME.pem` を実際の値に書き換えてから実行してください。
