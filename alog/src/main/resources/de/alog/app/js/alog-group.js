var app = angular.module('AlogApp');

app.controller('GroupCtrl', ['$scope', '$filter', 'alog', function ($scope, $filter, alog) {
	var groupVM = new GroupVM($scope, $filter, alog);
	$scope.groupVM = groupVM;
	$scope.$on("logentries.loading", function(evt){
		groupVM.loadEnabledGroupStats();
	});
	$scope.$watchCollection("logVM.featureGrouped", function(newv, oldv){
		angular.forEach(newv, function(v,k){
			if (newv[k] && !oldv[k]) {
				groupVM.loadGroupStats(k);
			}
		});
	});
}]);

function GroupVM(scope, filter, alog) {
	var that = this;
	this.alog = alog;
	this.filter = filter;
	this.scope = scope;
	this.groupData = {};
	
	this.loadEnabledGroupStats = function() {
		angular.forEach(that.scope.logVM.featureGrouped, function(v,k) {
			if (v) {
				that.loadGroupStats(k);
			}
		});
	};
	
	this.loadGroupStats = function(category) {
		var settings = angular.copy(that.scope.logVM.querySettings);
		
		settings.qgroup = category;
		settings.qdetail = "count";
		
		alog.load(settings).then(function(data) {
			that.groupData[category] = [];
			var sum = 0;
			angular.forEach(data.group.slice(0, 5), function(e) {
				this.push({label: e[category], data: e.count/*, color:'#f00'*/});
				sum = sum + e.count;
			}, that.groupData[category]);
			if (sum < data.count) {
				that.groupData[category].push({label: "Sonstige", data: (data.count - sum)})
			}
			that.updateGroupView(category)
		});
	};
	
	this.updateGroupView = function(category) {
		var plc = $("#group_view_"+category);
		plc.unbind();
		$.plot(plc, that.groupData[category], {
			series: {
		        pie: {
		            show: true,
		            radius: 1,
		            label: {
		                show: true,
		                radius: 3/4,
		                formatter: that.labelFormatter,
		                background: {
		                    opacity: 0.5
		                }
		            }
		        }
		    },
		    legend: {
		        show: true
		    },
		    grid: {
				hoverable: false,
				clickable: true
			},

		});
		plc.bind("plotclick", function(event,pos,obj) {
			if (obj.series.label != "Sonstige") {
				that.scope.logVM.querySettings[category] = obj.series.label;
				that.scope.$emit("parameterChanged");
			}
		});
//		colors: ["#428bca"]
	};
	
	this.labelFormatter = function labelFormatter(label, series) {
		//return "<div style='font-size:7pt; text-align:center; padding:2px; color:white;'>" + label + "<br/>" + that.filter('number')(series.datapoints.points[1], 0) + "</div>";
		return "<div style='font-size:7pt; text-align:center; padding:2px; color:white;'>" + that.filter('number')(series.datapoints.points[1], 0) + "</div>";
	}
	
}


