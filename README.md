# apis-main

## Introduction
apis-mainはSony CSLが開発した自律分散制御の電力相互融通ソフトウェアである。    
apis-mainは各ノード毎にインストールされ、定期的に自身のBattery残容量を取得し、バッテリ残容量に  
よって決められた行動ルールに従ってapis-main間でネゴシエーションを  行って自動でノード間の  
電力融通を実現するソフトウェアである。  
apis-main間のネゴシエーションはイーサネットなどのコミュニケーションラインが用いられ電力融通は  
DC Grid上で直流にて行われる。  
apis-mainは集中管理制御のように中央で制御を行うソフトウェアは存在せず、すべて同一のソフトウェア  
であるapis-mainのみで自律分散制御が行われる。  

![キャプチャ](https://user-images.githubusercontent.com/71874910/94899039-87ea0600-04cd-11eb-96a0-afa5466b3742.PNG)

## Getting Started
```bash

$ mkdir apis-common_build
$ cd apis-common_build
$ git clone https://github.com/SonyCSL/apis-common.git
$ cd apis-common
$ mvn install
$ cd ../../
$ mkdir apis-main_build
$ cd apis-main_build
$ git cone https://github.com/SonyCSL/apis-main.git
$ cd apis-main
$ mvn package
$ cd ../../
$ mkdir apis-main_exe
$ cp ./apis-main_build/apis-main/target/apis-main-*-fat.jar ./apis-main_exe
$ cp ./apis-main_build/apis-main/setting_files/* ./apis-main_exe
$ bash start.sh

```

## Usage
Refer to the specification for various settings.


## Documentation
&emsp;[apis-main_specificaton(JP)](https://github.com/oes-github/apis-main/blob/master/doc/jp/apis-main_specification.md)


## License
&emsp;[Apache License Version 2.0](https://github.com/oes-github/apis-main/blob/master/LICENSE)


## Notice
&emsp;[Notice](https://github.com/oes-github/apis-main/blob/master/NOTICE.md)
