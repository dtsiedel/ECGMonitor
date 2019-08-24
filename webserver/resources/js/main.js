var websocket_url = "ws://192.168.7.2:8888/ws";
var ws;
var subscribed = null;
var graph_x_points = 30;
var data = [];

function change_subscription(uuid) {
  if (subscribed != null) {
    ws.send(JSON.stringify({"unsubscribe": subscribed}));
    data = [];
  }
  ws.send(JSON.stringify({"subscribe": uuid}));
  subscribed = uuid;
}

function update_sources(source_list) {
  var sources_bar = document.querySelector("#sources_bar");
  sources_bar.innerHTML = '';
  var length = source_list.length;
  for (var i = 0; i < length; i++) {
    var source = source_list[i];
    var new_node = document.createElement(source.displayName);
    var on_click_string = 'onClick="change_subscription(\'' + source.uuid + '\')";';
    new_node.innerHTML = '<button ' + on_click_string + '>' + source.displayName + '</button>'
    sources_bar.appendChild(new_node);
  }
}

function update_data(data_entry) {
  data.push({x: new Date(), y: data_entry});
  if (data.length > graph_x_points) {
    data.shift();
  }
  chart.render();
}

function process_update(json_string) {
  var data = JSON.parse(json_string);
  if ('sources' in data) { update_sources(data.sources); }
  if ('publish' in data) { update_data(data.publish); }
}

function main() {
  ws = new WebSocket(websocket_url);
  ws.onmessage = msg => process_update(msg.data);
  ws.onopen = () => console.log("Opened ws");

  chart = new CanvasJS.Chart("container", {
    title: {text: "Heart Data"},
    axisY: {includeZero: false, labelFormatter: e => ""},
    axisX: {labelFormatter: e => ""},
    data: [{type: "line", dataPoints: data}]
  });
  update_sources([]);

  chart.render();
}

document.addEventListener("DOMContentLoaded", function(){
  main();
});
