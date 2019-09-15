```NOTE: this project is on hold because my BeagleBone started experiencing some kind of issue with connecting to my laptop, after not touching it for a few weeks. For that reason the demo video below is made with randomly-generated data instead of the real ECG data.```

![Demo](https://media.giphy.com/media/S4H2mBZwOZghZiu95z/giphy.gif)

# Building and Running

## Setup

Connect your ECG device (like the AD8232) to the machine that will run this code (I used a BeagleBone Black). A guide like [this one](https://learn.sparkfun.com/tutorials/ad8232-heart-rate-monitor-hookup-guide/all) on SparkFun should show you how to wire it, where to place the leads on your body, etc.

## The Webserver
```
cd webserver
./gradlew
```

## The Python Client
```
cd python_client
pip3 install -r requirements.txt
python3 gather_ecg.py # python 3.4 or greater
```

## The Webpage

With the webserver up, you can see the page at `localhost:8888`. It will redirect to the landing page. Click the name of your device (currently some random number tacked onto "Heartbeat Sensor") and you should start to see the data come streaming through.
