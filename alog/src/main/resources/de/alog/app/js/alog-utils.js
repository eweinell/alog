var app = angular.module('AlogApp');

app.directive('alogScrolled', ['$window', '$document', function($window, $document) {
    return {
    	link: function(scope, elem, attr) {
	    	var scrollThreshold = parseInt(attr.scrollThreshold, 10) || 50;
	    	
	    	var handler = function(event) {
	    		var windowBottom = $window.innerHeight + $window.scrollY;
	            var elemBottom = elem.offset().top + elem.height();
	            var remaining = elementBottom - windowBottom;
	            var shouldScroll = remaining <= $window.height();
	             
	            if (shouldScroll) {
	                scope.$apply(attr.alogScrolled);
	            }
	        };
	        $document.on('scroll', handler);
	    	scope.$on('$destroy', function() {
	            return $window.off('scroll', handler);
	        });
    	}
    };
}]);

app.factory('alogUtils', [function(){
	return {
		stdDateFormat : {
			datetime: 'dd.MM.yyyy/HH:mm:ss',
			date: 'dd.MM.yyyy'
		},
			
		dateWithDeltaDays : function(days) {
			var d = new Date();
			d.setDate(d.getDate()+days);
			return d;
		},
		
		dateFromString : function(str) {
			moment(str, "DD.MM.YYYY/HH:mm:ss,SSS").toDate();
		}
	};
}]);

app.filter('wbrify', ['$sce', function($sce) {
	return function(input) {
		return $sce.trustAsHtml(input.replace(/([-._/\\])/g, "$1<wbr>"));
	};
}]);