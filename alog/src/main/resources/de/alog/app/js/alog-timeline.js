var app = angular.module('AlogApp');

app.controller('TimelineCtrl', ['$scope', '$filter', 'alog', 'alogUtils', function ($scope, $filter, alog, alogUtils) {
	var timelineVM = new TimelineVM($scope, $filter, alog, alogUtils);
	$scope.timelineVM = timelineVM;
	$scope.$on("logentries.loading", function(evt){
		timelineVM.loadTimeline();
	});
	
	$("<div id='timeline-tooltip' class='popover'><div class='popover-inner'><div id='timeline-tooltip-content' class='popover-content'></div></div></div>").css({
		position: "absolute",
		display: "none",
		opacity: 0.80
	}).fadeIn(200).appendTo("body");
}]);

function TimelineVM(scope, filter, alog, utils) {
	var that = this;
	this.scope = scope;
	this.filter = filter;
	this.alog = alog;
	this.utils = utils;
	
	this.timelineResolutions = [
	     ['7d', '7 Tage'],
	     ['2d', '2 Tage'],
	     ['1d', '1 Tag'],
	     ['8h', '8 Stunden'],
	     ['4h', '4 Stunden'],
	     ['2h', '2 Stunden'],
	     ['1h', '1 Stunde'],
	     ['30m', '30 Minuten'],
	     ['15m', '15 Minuten'],
	     ['5m', '5 Minuten'],
	     ['1m', '1 Minute']];
	
	this.timelineStart = {}
	this.timelineEnd = {}
	this.timelineRes = 0
	this.timelineTotal = 0
	this.timelineInputRes = ''
	this.timeline = [[]];
	
	this.loadTimeline = function() {
		var settings = angular.copy(that.scope.logVM.querySettings);
		
		scope.logVM.uiSettings
		settings.qgroup = "timestamp" + (that.timelineInputRes ? ":" + that.timelineInputRes : "");
		settings.qdetail = "count";
		
		alog.load(settings).then(function(data) {
			var m1 = moment(data.start, "DD.MM.yyyy/HH:mm:ss,SSS");
			var m2 = moment(data.end, "DD.MM.yyyy/HH:mm:ss,SSS");
			that.timelineStart = moment(data.start, "DD.MM.yyyy/HH:mm:ss,SSS").valueOf()
			that.timelineEnd = moment(data.end, "DD.MM.yyyy/HH:mm:ss,SSS").valueOf()
			that.timelineRes = data.period.seconds;
			that.timelineTotal = data.count;
			
			that.timeline = [[]];
			angular.forEach(data.group, function(e) {
				this.push([moment(e.groupStart, "DD.MM.yyyy/HH:mm:ss,SSS").valueOf(), e.count]);
			}, that.timeline);
			that.updateTimeline();
		});
	}
	
	this.updateTimeline = function() {
		var plc = $("#timeline_chart");
		plc.unbind();
		$.plot(plc, [that.timeline], {
			series: {
				bars: {
					show: true,
					barWidth: that.timelineRes * 1000,
					align: "center"
				}
			},
			xaxis: {
				mode: "time",
				timeformat: "%d.%m.%y %H:%M",
				min: that.timelineStart,
				max: that.timelineEnd
			},
			selection: {
				mode: "x"
			},
			grid: {
				hoverable: true,
				clickable: false
			},
			colors: ["#428bca"]
		});
		plc.bind("plotselected", function(e,ranges){
			that.scope.logVM.uiSettings.dateFrom = new Date(ranges.xaxis.from);
			that.scope.logVM.uiSettings.dateTill = new Date(ranges.xaxis.to);
			that.scope.logVM.uiSettings.dateMode = "from_till";
			that.scope.$emit("parameterChanged");
		});
		plc.bind("plothover", function(e,pos,item){
			if (item) {
				var date = new Date(item.datapoint[0]);
				var dlabel = that.filter('date')(date, that.utils.stdDateFormat.datetime);
				var nlabel = that.filter('number')(item.datapoint[1], 0)
				$("#timeline-tooltip-content").html(dlabel + ": " + nlabel + " Eintr&auml;ge")
				$("#timeline-tooltip").css({top: item.pageY+5, left: item.pageX+5}).fadeIn(200);
			} else {
				$("#timeline-tooltip").hide();
			}
		})
	}
}
